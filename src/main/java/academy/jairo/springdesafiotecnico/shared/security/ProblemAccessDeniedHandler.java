package academy.jairo.springdesafiotecnico.shared.security;

import java.io.IOException;

import academy.jairo.springdesafiotecnico.shared.web.ProblemTypes;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * {@code 403} em ProblemDetail. O status e o header {@code WWW-Authenticate: Bearer error="insufficient_scope"}
 * vêm do handler do Spring Security.
 */
@Component
class ProblemAccessDeniedHandler implements AccessDeniedHandler {

	private final AccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();

	private final ProblemResponseWriter writer;

	ProblemAccessDeniedHandler(ProblemResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException, ServletException {
		bearer.handle(request, response, accessDeniedException);
		writer.write(request, response, HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Acesso negado",
				"O token não tem o escopo exigido pela operação.");
	}

}
