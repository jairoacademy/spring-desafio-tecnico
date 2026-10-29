package academy.jairo.springdesafiotecnico.activation.infrastructure.provider;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import academy.jairo.springdesafiotecnico.TestcontainersConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ProviderGatewayIntegrationTests {

	private static final String ACTIVATE = "/provider/v1/activate";

	@RegisterExtension
	static WireMockExtension provider = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	@DynamicPropertySource
	static void providerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.http.serviceclient.provider.base-url", provider::baseUrl);
		registry.add("spring.http.serviceclient.provider.read-timeout", () -> "500ms");
	}

	@Autowired
	private ProviderGateway gateway;

	@Test
	void okIsAccepted() {
		stubStatus(200);

		assertThat(activate()).isEqualTo(new ProviderOutcome.Accepted());
	}

	@Test
	void sendsPayloadAndIdempotencyKey() {
		stubStatus(200);
		UUID id = UUID.randomUUID();

		gateway.activate(id, "EXT-1", "CUST-1", "INTERNET-500");

		provider.verify(1, postRequestedFor(urlEqualTo(ACTIVATE))
			.withHeader("Idempotency-Key", equalTo(id.toString()))
			.withHeader("Content-Type", containing("application/json"))
			.withRequestBody(equalToJson("""
					{"externalId":"EXT-1","customerId":"CUST-1","productCode":"INTERNET-500"}""")));
	}

	@ParameterizedTest(name = "{0} é rejeição")
	@ValueSource(ints = { 400, 404 })
	void definitiveClientErrorIsRejected(int status) {
		stubStatus(status);

		assertThat(activate()).isEqualTo(new ProviderOutcome.Rejected(status));
	}

	@ParameterizedTest(name = "{0} é falha temporária")
	@ValueSource(ints = { 500, 408, 429, 503 })
	void serverErrorTimeoutStatusAndThrottlingWithoutHeaderAreTemporary(int status) {
		stubStatus(status);

		assertThat(activate()).isEqualTo(new ProviderOutcome.TemporaryFailure(null));
	}

	@Test
	void readTimeoutIsTemporary() {
		provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

		assertThat(activate()).isEqualTo(new ProviderOutcome.TemporaryFailure(null));
	}

	@Test
	void connectionResetIsTemporary() {
		provider.stubFor(post(ACTIVATE).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

		assertThat(activate()).isEqualTo(new ProviderOutcome.TemporaryFailure(null));
	}

	@Test
	void retryAfterInSecondsSchedulesFromNow() {
		provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "120")));
		Instant before = Instant.now();

		ProviderOutcome outcome = activate();

		Instant after = Instant.now();
		assertThat(outcome).isInstanceOfSatisfying(ProviderOutcome.TemporaryFailure.class,
				failure -> assertThat(failure.retryAt()).isBetween(before.plusSeconds(120), after.plusSeconds(120)));
	}

	@Test
	void retryAfterAsHttpDateIsUsedAsIs() {
		Instant retryAt = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
		String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(retryAt.atOffset(ZoneOffset.UTC));
		provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(503).withHeader("Retry-After", httpDate)));

		assertThat(activate()).isEqualTo(new ProviderOutcome.TemporaryFailure(retryAt));
	}

	@Test
	void invalidRetryAfterFallsBackToBackoff() {
		provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "logo")));

		assertThat(activate()).isEqualTo(new ProviderOutcome.TemporaryFailure(null));
	}

	private ProviderOutcome activate() {
		return gateway.activate(UUID.randomUUID(), "EXT-1", "CUST-1", "INTERNET-500");
	}

	private static void stubStatus(int status) {
		provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(status)));
	}

}
