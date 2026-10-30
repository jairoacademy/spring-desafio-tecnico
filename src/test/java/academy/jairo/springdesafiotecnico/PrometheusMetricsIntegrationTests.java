package academy.jairo.springdesafiotecnico;

import java.util.UUID;

import academy.jairo.springdesafiotecnico.activation.application.ActivationDispatcher;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// AutoConfigureMetrics: o Boot desliga a exportação de métricas nos testes.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
class PrometheusMetricsIntegrationTests {

	private static final String ACTIVATE = "/provider/v1/activate";

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

	@Test
	void exposesApiLatencyByRouteTemplate() throws Exception {
		mockMvc.perform(get("/api/v1/requests").with(TestTokens.readWrite())).andExpect(status().isOk());

		assertThat(scrape()).containsPattern("http_server_requests_seconds_(count|bucket)\\{[^}]*uri=\"/api/v1/requests\"");
	}

	@Test
	void doesNotUseBusinessIdentifiersAsLabels() throws Exception {
		String externalId = "EXT-METRICS-" + UUID.randomUUID();
		String id = JsonPath.read(create(externalId), "$.id");
		mockMvc.perform(get("/api/v1/requests/{id}", id).with(TestTokens.readWrite())).andExpect(status().isOk());

		String metrics = scrape();

		assertThat(metrics).contains("uri=\"/api/v1/requests/{id}\"");
		assertThat(metrics).doesNotContain(id).doesNotContain(externalId).doesNotContain("CUST-METRICS");
	}

	@Test
	void exposesProviderLatencyAndErrors() throws Exception {
		provider.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(ACTIVATE))
			.willReturn(aResponse().withStatus(500)));
		create("EXT-METRICS-" + UUID.randomUUID());

		dispatcher.dispatchDue();

		assertThat(scrape().lines())
			.anySatisfy(line -> assertThat(line).startsWith("http_client_requests_seconds_count")
				.contains("uri=\"" + ACTIVATE + "\"")
				.contains("status=\"500\""));
	}

	@Test
	void countsRequestsByStatus() throws Exception {
		provider.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(ACTIVATE))
			.willReturn(aResponse().withStatus(200)));
		create("EXT-METRICS-" + UUID.randomUUID());

		dispatcher.dispatchDue();

		String metrics = scrape();
		for (String status : new String[] { "PENDING", "PROCESSING", "COMPLETED" }) {
			assertThat(metrics).containsPattern(
					"activation_requests_transitions_total\\{[^}]*status=\"" + status + "\"[^}]*\\} [1-9]");
		}
	}

	private String create(String externalId) throws Exception {
		return mockMvc
			.perform(post("/api/v1/requests").with(TestTokens.readWrite())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"externalId":"%s","customerId":"CUST-METRICS","productCode":"INTERNET-500"}"""
					.formatted(externalId)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private String scrape() throws Exception {
		return mockMvc.perform(get("/actuator/prometheus"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

}
