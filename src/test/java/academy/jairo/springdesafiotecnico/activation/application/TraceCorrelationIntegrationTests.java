package academy.jairo.springdesafiotecnico.activation.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import academy.jairo.springdesafiotecnico.TestTokens;
import academy.jairo.springdesafiotecnico.TestcontainersConfiguration;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestRepository;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Worker chamado direto; backoff curto para a nova tentativa vencer em milissegundos.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "activation.dispatch.max-attempts=3", "activation.dispatch.backoff.base=200ms",
		"activation.dispatch.backoff.max=1s" })
@AutoConfigureMockMvc
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class TraceCorrelationIntegrationTests {

	private static final String ACTIVATE = "/provider/v1/activate";

	private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

	private static final String TRACEPARENT_PATTERN = "^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$";

	@RegisterExtension
	static WireMockExtension provider = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	@DynamicPropertySource
	static void providerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.http.serviceclient.provider.base-url", provider::baseUrl);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ActivationDispatcher dispatcher;

	@Autowired
	private ActivationRequestRepository repository;

	@BeforeEach
	void cleanUp() {
		repository.deleteAll();
		provider.resetAll();
	}

	@Test
	void providerReceivesTheTraceIdOfThePost() throws Exception {
		provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200)));
		createWithTrace("EXT-TRACE-OK");

		dispatcher.dispatchDue();

		provider.verify(1, postRequestedFor(urlEqualTo(ACTIVATE)).withHeader("traceparent",
				matching("^00-" + TRACE_ID + "-[0-9a-f]{16}-01$")));
	}

	@Test
	void dispatchLogCarriesTheTraceIdOfThePost(CapturedOutput output) throws Exception {
		provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(500)));
		createWithTrace("EXT-TRACE-LOG");

		dispatcher.dispatchDue();

		assertThat(output.getOut().lines().filter(line -> line.startsWith("{")))
			.anySatisfy(line -> {
				assertThat((String) JsonPath.read(line, "$.message")).contains("Provedor respondeu 500");
				assertThat((String) JsonPath.read(line, "$.traceId")).isEqualTo(TRACE_ID);
			});
	}

	@Test
	void retryStaysInTheSameTrace() throws Exception {
		provider.stubFor(post(ACTIVATE).inScenario("retry")
			.whenScenarioStateIs(Scenario.STARTED)
			.willReturn(aResponse().withStatus(500))
			.willSetStateTo("second"));
		provider.stubFor(post(ACTIVATE).inScenario("retry")
			.whenScenarioStateIs("second")
			.willReturn(aResponse().withStatus(200)));
		createWithTrace("EXT-TRACE-RETRY");

		dispatcher.dispatchDue();
		await().atMost(Duration.ofSeconds(5)).until(() -> dispatcher.dispatchDue() == 1);

		List<?> calls = provider.findAll(postRequestedFor(urlEqualTo(ACTIVATE)).withHeader("traceparent",
				matching("^00-" + TRACE_ID + "-[0-9a-f]{16}-01$")));
		assertThat(calls).hasSize(2);
	}

	@Test
	void rowWithoutStoredContextIsSentWithAValidTraceparent() {
		provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200)));
		repository.save(ActivationRequest.create(TestTokens.DEFAULT_CLIENT, "EXT-NO-TRACE", "CUST-1",
				"INTERNET-500", Instant.now(), null));

		dispatcher.dispatchDue();

		provider.verify(1, postRequestedFor(urlEqualTo(ACTIVATE)).withHeader("traceparent",
				matching(TRACEPARENT_PATTERN)));
	}

	private void createWithTrace(String externalId) throws Exception {
		String body = mockMvc
			.perform(MockMvcRequestBuilders.post("/api/v1/requests").with(TestTokens.readWrite())
				.header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"externalId":"%s","customerId":"CUST-1","productCode":"INTERNET-500"}""".formatted(externalId)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("PENDING");
	}

}
