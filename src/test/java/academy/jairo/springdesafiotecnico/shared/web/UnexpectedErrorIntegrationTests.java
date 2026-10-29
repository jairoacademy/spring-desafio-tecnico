package academy.jairo.springdesafiotecnico.shared.web;

import java.util.UUID;

import academy.jairo.springdesafiotecnico.TestTokens;
import academy.jairo.springdesafiotecnico.TestcontainersConfiguration;
import academy.jairo.springdesafiotecnico.activation.application.ActivationRequestService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class UnexpectedErrorIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ActivationRequestService service;

	@Test
	void unexpectedFailureReturnsGenericProblemAndLogsSameTraceId(CapturedOutput output) throws Exception {
		given(service.findById(anyString(), any(UUID.class))).willThrow(new RuntimeException("segredo interno"));

		String body = mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(TestTokens.readWrite()))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://problems.ativacao.example/internal-error"))
			.andExpect(jsonPath("$.title").value("Erro interno"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).doesNotContain("segredo interno", "Exception").doesNotContainPattern("at [a-z][\\w.$]*\\(");
		String traceId = JsonPath.read(body, "$.traceId");
		assertThat(traceId).matches("^[0-9a-f]{32}$");
		assertThat(output.getOut().lines().filter(line -> line.contains("ERROR")))
			.anySatisfy(line -> assertThat(line).contains(traceId).contains("Erro inesperado"));
		assertThat(output.getOut()).contains("segredo interno");
	}

}
