package academy.jairo.springdesafiotecnico;

import java.time.Duration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OtlpExportIntegrationTests {

	@Nested
	@Import(TestcontainersConfiguration.class)
	@SpringBootTest
	@AutoConfigureMockMvc
	@AutoConfigureTracing
	class WithoutEndpoint {

		@Autowired
		private ApplicationContext context;

		@Autowired
		private MockMvc mockMvc;

		@Test
		void startsWithoutSpanExporter() throws Exception {
			assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();

			mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
			mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
		}

	}

	@Nested
	@Import(TestcontainersConfiguration.class)
	@SpringBootTest(properties = "management.opentelemetry.tracing.export.schedule-delay=100ms")
	@AutoConfigureMockMvc
	@AutoConfigureTracing
	class WithEndpoint {

		@RegisterExtension
		static WireMockExtension collector = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

		@DynamicPropertySource
		static void collectorProperties(DynamicPropertyRegistry registry) {
			registry.add("management.tracing.export.otlp.enabled", () -> "true");
			registry.add("management.opentelemetry.tracing.export.otlp.endpoint",
					() -> collector.baseUrl() + "/v1/traces");
		}

		@Autowired
		private MockMvc mockMvc;

		@Test
		void exportsSpansToCollector() throws Exception {
			collector.stubFor(post(urlEqualTo("/v1/traces")).willReturn(aResponse().withStatus(200)));

			mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());

			await().atMost(Duration.ofSeconds(10))
				.untilAsserted(() -> collector.verify(postRequestedFor(urlEqualTo("/v1/traces"))));
		}

	}

}
