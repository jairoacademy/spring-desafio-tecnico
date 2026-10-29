package academy.jairo.springdesafiotecnico.activation.application;

import java.time.Duration;

import academy.jairo.springdesafiotecnico.TestTokens;
import academy.jairo.springdesafiotecnico.TestcontainersConfiguration;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Único teste com o agendamento ligado; o contexto é fechado no fim para o worker não seguir rodando.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "activation.dispatch.enabled=true", "activation.dispatch.interval=100ms" })
@AutoConfigureMockMvc
@DirtiesContext
class ActivationDispatchSchedulingIntegrationTests {

	private static final String ACTIVATE = "/provider/v1/activate";

	private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

	@RegisterExtension
	static WireMockExtension provider = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	@DynamicPropertySource
	static void providerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.http.serviceclient.provider.base-url", provider::baseUrl);
		registry.add("spring.http.serviceclient.provider.read-timeout", READ_TIMEOUT::toString);
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	void createdRequestIsCompletedInBackground() throws Exception {
		provider.stubFor(WireMock.post(ACTIVATE).willReturn(aResponse().withStatus(200)));

		String id = create("EXT-SCHEDULED");

		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> mockMvc.perform(get("/api/v1/requests/{id}", id).with(TestTokens.readWrite()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED")));
	}

	@Test
	void slowFailingProviderDoesNotDelayCreation() throws Exception {
		provider.stubFor(WireMock.post(ACTIVATE)
			.willReturn(aResponse().withStatus(500).withFixedDelay((int) READ_TIMEOUT.multipliedBy(2).toMillis())));
		create("EXT-WARMUP");
		long start = System.nanoTime();

		create("EXT-SLOW-PROVIDER");

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(READ_TIMEOUT);
	}

	/** Cria pela API e confere {@code 201} com {@code PENDING}; devolve o {@code id}. */
	private String create(String externalId) throws Exception {
		MvcResult result = mockMvc
			.perform(post("/api/v1/requests").with(TestTokens.readWrite())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"externalId":"%s","customerId":"CUST-1","productCode":"INTERNET-500"}""".formatted(externalId)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}

}
