package academy.jairo.springdesafiotecnico.shared.security;

import java.io.IOException;
import java.net.URI;

import academy.jairo.springdesafiotecnico.shared.web.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Escreve o corpo ProblemDetail dos erros de segurança, que acontecem no filtro, antes do Spring MVC. Status e
 * headers já foram definidos pelos handlers Bearer do Spring Security.
 */
@Component
class ProblemResponseWriter {

	private final ProblemDetails problemDetails;

	private final JsonMapper jsonMapper;

	ProblemResponseWriter(ProblemDetails problemDetails, JsonMapper jsonMapper) {
		// mesmo mixin do conversor do MVC: propriedades extras (traceId) no nível de cima do JSON
		this.problemDetails = problemDetails;
		this.jsonMapper = jsonMapper.rebuild().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();
	}

	void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, URI type, String title,
			String detail) throws IOException {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(type);
		problem.setTitle(title);
		problemDetails.complete(problem, request.getRequestURI());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		jsonMapper.writeValue(response.getOutputStream(), problem);
	}

}
