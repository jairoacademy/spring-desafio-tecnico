package academy.jairo.springdesafiotecnico.shared.web;

import java.net.URI;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Campos comuns a todo ProblemDetail da API ({@code type} padrão, {@code instance} e {@code traceId}), usados
 * pelo {@link ApiExceptionHandler} e pelos erros de segurança, que acontecem fora do Spring MVC.
 */
@Component
public class ProblemDetails {

	private final Tracer tracer;

	ProblemDetails(Tracer tracer) {
		this.tracer = tracer;
	}

	public void complete(ProblemDetail problem, String path) {
		if (problem.getType() == null) {
			// erros do MVC sem type próprio (405, 415, ...): valor padrão do RFC 9457, explícito no JSON
			problem.setType(ProblemTypes.ABOUT_BLANK);
		}
		problem.setInstance(URI.create(path));
		String traceId = currentTraceId();
		if (traceId != null) {
			problem.setProperty("traceId", traceId);
		}
	}

	private @Nullable String currentTraceId() {
		Span span = tracer.currentSpan();
		if (span == null) {
			return null;
		}
		String traceId = span.context().traceId();
		return traceId.isEmpty() ? null : traceId;
	}

}
