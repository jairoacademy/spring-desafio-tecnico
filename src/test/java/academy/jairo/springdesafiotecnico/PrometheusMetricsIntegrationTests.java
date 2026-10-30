package academy.jairo.springdesafiotecnico;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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

	@Autowired
	private MockMvc mockMvc;

	@Test
	void exposesApiLatencyByRouteTemplate() throws Exception {
		mockMvc.perform(get("/api/v1/requests").with(TestTokens.readWrite())).andExpect(status().isOk());

		assertThat(scrape()).containsPattern("http_server_requests_seconds_(count|bucket)\\{[^}]*uri=\"/api/v1/requests\"");
	}

	@Test
	void doesNotUseBusinessIdentifiersAsLabels() throws Exception {
		String externalId = "EXT-METRICS-" + UUID.randomUUID();
		String body = mockMvc
			.perform(post("/api/v1/requests").with(TestTokens.readWrite())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"externalId":"%s","customerId":"CUST-METRICS","productCode":"INTERNET-500"}"""
					.formatted(externalId)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String id = JsonPath.read(body, "$.id");
		mockMvc.perform(get("/api/v1/requests/{id}", id).with(TestTokens.readWrite())).andExpect(status().isOk());

		String metrics = scrape();

		assertThat(metrics).contains("uri=\"/api/v1/requests/{id}\"");
		assertThat(metrics).doesNotContain(id).doesNotContain(externalId).doesNotContain("CUST-METRICS");
	}

	private String scrape() throws Exception {
		return mockMvc.perform(get("/actuator/prometheus"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

}
