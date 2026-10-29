package academy.jairo.springdesafiotecnico.activation.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import academy.jairo.springdesafiotecnico.TestcontainersConfiguration;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus;
import academy.jairo.springdesafiotecnico.activation.domain.FailureReason;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestRepository;
import academy.jairo.springdesafiotecnico.activation.infrastructure.provider.ProviderOutcome;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Chama o dispatcher direto, sem o agendamento; backoff curto para a nova tentativa vencer em milissegundos.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "activation.dispatch.max-attempts=3", "activation.dispatch.backoff.base=200ms",
		"activation.dispatch.backoff.max=1s", "activation.dispatch.reconciliation.stale-after=1m" })
class ProviderIntegrationTests {

	private static final String ACTIVATE = "/provider/v1/activate";

	private static final String OWNER = "sistema-a";

	private static final Duration BACKOFF_BASE = Duration.ofMillis(200);

	@RegisterExtension
	static WireMockExtension provider = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	@DynamicPropertySource
	static void providerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.http.serviceclient.provider.base-url", provider::baseUrl);
		registry.add("spring.http.serviceclient.provider.read-timeout", () -> "500ms");
	}

	@Autowired
	private ActivationDispatcher dispatcher;

	@Autowired
	private ActivationRequestRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanUp() {
		repository.deleteAll();
	}

	@Test
	void okCompletesWithSingleCall() {
		stubStatus(200);
		ActivationRequest request = save("EXT-OK");

		dispatcher.dispatchDue();

		ActivationRequest current = reload(request);
		assertThat(current.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
		assertThat(current.getFailureReason()).isNull();
		assertThat(current.getAttempts()).isZero();
		assertCalls(request, 1);
	}

	@Test
	void badRequestFailsWithoutAnotherCall() {
		stubStatus(400);
		ActivationRequest request = save("EXT-400");

		dispatcher.dispatchDue();
		dispatcher.dispatchDue();

		ActivationRequest current = reload(request);
		assertThat(current.getStatus()).isEqualTo(ActivationStatus.FAILED);
		assertThat(current.getFailureReason()).isEqualTo(FailureReason.PROVIDER_REJECTED);
		assertThat(current.getAttempts()).isZero();
		assertCalls(request, 1);
	}

	@Test
	void serverErrorSchedulesRetryWithinBackoff() {
		stubStatus(500);
		ActivationRequest request = save("EXT-500");
		Instant before = Instant.now();

		dispatcher.dispatchDue();

		Instant after = Instant.now();
		ActivationRequest current = reload(request);
		assertThat(current.getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertThat(current.getAttempts()).isEqualTo(1);
		assertThat(current.getNextAttemptAt()).isBetween(before.plus(BACKOFF_BASE.dividedBy(2)),
				after.plus(BACKOFF_BASE));
	}

	@Test
	void readTimeoutSchedulesRetry() {
		provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200).withFixedDelay(1000)));
		ActivationRequest request = save("EXT-TIMEOUT");

		dispatcher.dispatchDue();

		ActivationRequest current = reload(request);
		assertThat(current.getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertThat(current.getAttempts()).isEqualTo(1);
		assertThat(current.getNextAttemptAt()).isAfter(current.getUpdatedAt());
	}

	@Test
	void retryAfterSchedulesAtProviderInstant() {
		provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "120")));
		ActivationRequest request = save("EXT-429");
		Instant before = Instant.now();

		dispatcher.dispatchDue();

		Instant after = Instant.now();
		ActivationRequest current = reload(request);
		assertThat(current.getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertThat(current.getAttempts()).isEqualTo(1);
		assertThat(current.getNextAttemptAt()).isBetween(before.plusSeconds(120).minusNanos(1000),
				after.plusSeconds(120));
	}

	@Test
	void retryAfterServerErrorCompletesWithSameIdempotencyKey() {
		provider.stubFor(post(ACTIVATE).inScenario("retry")
			.whenScenarioStateIs(Scenario.STARTED)
			.willReturn(aResponse().withStatus(500))
			.willSetStateTo("recovered"));
		provider.stubFor(post(ACTIVATE).inScenario("retry")
			.whenScenarioStateIs("recovered")
			.willReturn(aResponse().withStatus(200)));
		ActivationRequest request = save("EXT-RETRY");

		await().atMost(Duration.ofSeconds(5)).until(() -> {
			dispatcher.dispatchDue();
			return reload(request).getStatus() == ActivationStatus.COMPLETED;
		});

		assertThat(reload(request).getAttempts()).isEqualTo(1);
		assertCalls(request, 2);
		assertThat(provider.findAll(postRequestedFor(urlEqualTo(ACTIVATE))))
			.extracting(call -> call.getHeader("Idempotency-Key"))
			.containsExactly(request.getId().toString(), request.getId().toString());
	}

	@Test
	void failsAfterMaxAttemptsWithExactlyThatManyCalls() {
		stubStatus(500);
		ActivationRequest request = save("EXT-MAX");

		await().atMost(Duration.ofSeconds(10)).until(() -> {
			dispatcher.dispatchDue();
			return reload(request).getStatus() == ActivationStatus.FAILED;
		});
		dispatcher.dispatchDue();

		ActivationRequest current = reload(request);
		assertThat(current.getFailureReason()).isEqualTo(FailureReason.MAX_ATTEMPTS_EXCEEDED);
		assertThat(current.getAttempts()).isEqualTo(3);
		assertCalls(request, 3);
	}

	@Test
	void sendsPendingRequestStoredBeforeRestart() {
		stubStatus(200);
		// gravada direto no banco, sem passar pela aplicação, como uma linha de antes de uma parada
		UUID id = UUID.randomUUID();
		Timestamp past = Timestamp.from(Instant.now().minusSeconds(3600));
		jdbcTemplate.update("""
				INSERT INTO activation_request (id, client_id, external_id, customer_id, product_code, status,
				                                attempts, next_attempt_at, created_at, updated_at, version)
				VALUES (?, 'sistema-a', 'EXT-OLD', 'CUST-1', 'INTERNET-500', 'PENDING', 0, ?, ?, ?, 0)""", id, past,
				past, past);

		dispatcher.dispatchDue();

		assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ActivationStatus.COMPLETED);
		provider.verify(1,
				postRequestedFor(urlEqualTo(ACTIVATE)).withHeader("Idempotency-Key", equalTo(id.toString())));
	}

	@Test
	void doesNotSendRequestWithFutureAttempt() {
		stubStatus(200);
		ActivationRequest request = repository
			.save(ActivationRequest.create(OWNER, "EXT-FUTURE", "CUST-1", "INTERNET-500",
					Instant.now().plusSeconds(60)));

		dispatcher.dispatchDue();

		assertThat(reload(request).getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertCalls(request, 0);
	}

	@Test
	void reconciliationReturnsStaleProcessingToPending() {
		ActivationRequest request = saveProcessing("EXT-STALE");
		ageUpdatedAt(request, Duration.ofMinutes(2));

		dispatcher.reconcileStale();

		ActivationRequest current = reload(request);
		assertThat(current.getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertThat(current.getAttempts()).isEqualTo(1);
		assertThat(current.getNextAttemptAt()).isAfter(current.getUpdatedAt());
	}

	@Test
	void reconciliationLeavesRecentProcessingAlone() {
		ActivationRequest request = saveProcessing("EXT-RECENT");

		dispatcher.reconcileStale();

		ActivationRequest current = reload(request);
		assertThat(current.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
		assertThat(current.getAttempts()).isZero();
		assertThat(current.getVersion()).isEqualTo(request.getVersion());
	}

	@Test
	void reconciliationAtAttemptLimitFails() {
		ActivationRequest request = saveProcessing("EXT-STALE-LIMIT");
		jdbcTemplate.update("UPDATE activation_request SET attempts = 2 WHERE id = ?", request.getId());
		ageUpdatedAt(request, Duration.ofMinutes(2));

		dispatcher.reconcileStale();

		ActivationRequest current = reload(request);
		assertThat(current.getStatus()).isEqualTo(ActivationStatus.FAILED);
		assertThat(current.getFailureReason()).isEqualTo(FailureReason.MAX_ATTEMPTS_EXCEEDED);
		assertThat(current.getAttempts()).isEqualTo(3);
	}

	@Test
	void lateResultAfterReconciliationIsDiscarded() {
		ActivationRequest request = save("EXT-LATE");
		ClaimedRequest claimed = dispatcher.claimDue().getFirst();
		ageUpdatedAt(request, Duration.ofMinutes(2));
		dispatcher.reconcileStale();

		dispatcher.applyOutcome(claimed, new ProviderOutcome.Accepted());

		ActivationRequest current = reload(request);
		assertThat(current.getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertThat(current.getAttempts()).isEqualTo(1);
	}

	private ActivationRequest saveProcessing(String externalId) {
		ActivationRequest request = ActivationRequest.create(OWNER, externalId, "CUST-1", "INTERNET-500",
				Instant.now());
		request.startProcessing(Instant.now());
		return repository.save(request);
	}

	// simula a instância que reservou a solicitação e parou antes de gravar o resultado
	private void ageUpdatedAt(ActivationRequest request, Duration age) {
		jdbcTemplate.update("UPDATE activation_request SET updated_at = ? WHERE id = ?",
				Timestamp.from(Instant.now().minus(age)), request.getId());
	}

	private ActivationRequest save(String externalId) {
		return repository.save(ActivationRequest.create(OWNER, externalId, "CUST-1", "INTERNET-500", Instant.now()));
	}

	private ActivationRequest reload(ActivationRequest request) {
		return repository.findById(request.getId()).orElseThrow();
	}

	private static void assertCalls(ActivationRequest request, int expected) {
		provider.verify(expected, postRequestedFor(urlEqualTo(ACTIVATE)).withHeader("Idempotency-Key",
				equalTo(request.getId().toString())));
	}

	private static void stubStatus(int status) {
		provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(status)));
	}

}
