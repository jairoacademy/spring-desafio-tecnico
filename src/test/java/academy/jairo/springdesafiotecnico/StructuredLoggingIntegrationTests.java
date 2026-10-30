package academy.jairo.springdesafiotecnico;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import academy.jairo.springdesafiotecnico.activation.application.ActivationRequestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ActivationRequestService service;

	@Test
	void errorLogIsJsonWithTheTraceIdOfTheProblemDetail(CapturedOutput output) throws Exception {
		given(service.findById(anyString(), any(UUID.class))).willThrow(new RuntimeException("falha simulada"));

		String body = mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(TestTokens.readWrite()))
			.andExpect(status().isInternalServerError())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String traceId = JsonPath.read(body, "$.traceId");
		assertThat(output.getOut().lines().filter(line -> line.startsWith("{")))
			.anySatisfy(line -> {
				assertThat((String) JsonPath.read(line, "$.level")).isEqualTo("ERROR");
				assertThat((String) JsonPath.read(line, "$.traceId")).isEqualTo(traceId);
				assertThat((String) JsonPath.read(line, "$.spanId")).matches("^[0-9a-f]{16}$");
			});
	}

	@Test
	void invalidTokenNeverReachesTheLogs(CapturedOutput output) throws Exception {
		String token = "segredo-do-token.invalido.assinatura";

		mockMvc.perform(get("/api/v1/requests").header("Authorization", "Bearer " + token))
			.andExpect(status().isUnauthorized());

		assertThat(output.getAll()).doesNotContain(token);
	}

}
