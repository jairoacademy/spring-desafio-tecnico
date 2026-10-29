package academy.jairo.springdesafiotecnico.shared.security;

import java.io.IOException;

import academy.jairo.springdesafiotecnico.shared.web.ProblemTypes;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * {@code 401} em ProblemDetail. O status e o header {@code WWW-Authenticate: Bearer} (RFC 6750) vêm do handler
 * do Spring Security; o {@code detail} é fixo para não expor a mensagem da validação do token.
 */
@Component
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final AuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();

	private final ProblemResponseWriter writer;

	ProblemAuthenticationEntryPoint(ProblemResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException, ServletException {
		bearer.commence(request, response, authException);
		writer.write(request, response, HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED, "Não autenticado",
				"Token de acesso ausente ou inválido.");
	}

}
