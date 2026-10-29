package academy.jairo.springdesafiotecnico.shared.web;

import java.net.URI;
import java.util.List;
import java.util.Map;

import academy.jairo.springdesafiotecnico.activation.application.ActivationRequestConflictException;
import academy.jairo.springdesafiotecnico.activation.application.ActivationRequestNotFoundException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Ponto único de erro da API: todo corpo de erro sai em ProblemDetail com {@code traceId} e
 * {@code instance}, inclusive os erros que o próprio Spring MVC gera (405, 415, ...).
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private static final String VALIDATION_TITLE = "Dados inválidos";

	private final Tracer tracer;

	ApiExceptionHandler(Tracer tracer) {
		this.tracer = tracer;
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> fieldError(error.getField(), error))
			.toList();
		return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
			HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = ex.getParameterValidationResults()
			.stream()
			.flatMap(result -> result.getResolvableErrors()
				.stream()
				.map(error -> fieldError(parameterName(result.getMethodParameter().getParameterName()), error)))
			.toList();
		return handleExceptionInternal(ex, validationProblem(errors), headers, HttpStatus.BAD_REQUEST, request);
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		String name = (ex instanceof MethodArgumentTypeMismatchException mismatch) ? mismatch.getName()
				: parameterName(ex.getPropertyName());
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_PARAMETER, "Parâmetro inválido",
				"Valor inválido para o parâmetro '" + name + "'.");
		return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		// detail fixo: a mensagem do parser JSON cita classes e posições internas
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_BODY,
				"Corpo da requisição inválido",
				"O corpo da requisição não é um JSON válido ou tem campo com tipo incompatível.");
		return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
	}

	@ExceptionHandler(ActivationRequestNotFoundException.class)
	@Nullable ResponseEntity<Object> handleNotFound(ActivationRequestNotFoundException ex, WebRequest request) {
		ProblemDetail problem = problem(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Solicitação não encontrada",
				"Não existe solicitação com id " + ex.getId() + ".");
		return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
	}

	@ExceptionHandler(ActivationRequestConflictException.class)
	@Nullable ResponseEntity<Object> handleConflict(ActivationRequestConflictException ex, WebRequest request) {
		// sem id nem valores do registro existente: só quem o criou deve lê-lo
		ProblemDetail problem = problem(HttpStatus.CONFLICT, ProblemTypes.DUPLICATE_EXTERNAL_ID,
				"Solicitação duplicada",
				"Já existe solicitação com externalId " + ex.getExternalId() + " e conteúdo diferente.");
		problem.setProperty("conflictingFields", ex.getConflictingFields());
		return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
	}

	@ExceptionHandler(Exception.class)
	@Nullable ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Erro inesperado ao processar {}", path(request), ex);
		ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR,
				"Erro interno", "Ocorreu um erro inesperado. Informe o traceId ao suporte.");
		return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
	}

	@Override
	protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (body instanceof ProblemDetail problem) {
			if (problem.getType() == null) {
				// erros do MVC sem type próprio (405, 415, ...): valor padrão do RFC 9457, explícito no JSON
				problem.setType(ProblemTypes.ABOUT_BLANK);
			}
			problem.setInstance(URI.create(path(request)));
			String traceId = currentTraceId();
			if (traceId != null) {
				problem.setProperty("traceId", traceId);
			}
		}
		return super.createResponseEntity(body, headers, statusCode, request);
	}

	private @Nullable String currentTraceId() {
		Span span = tracer.currentSpan();
		if (span == null) {
			return null;
		}
		String traceId = span.context().traceId();
		return traceId.isEmpty() ? null : traceId;
	}

	private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_ERROR, VALIDATION_TITLE,
				"Um ou mais campos são inválidos.");
		problem.setProperty("errors", errors);
		return problem;
	}

	private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(type);
		problem.setTitle(title);
		return problem;
	}

	private static Map<String, String> fieldError(String field, MessageSourceResolvable error) {
		String message = error.getDefaultMessage();
		return Map.of("field", field, "message", (message != null) ? message : "valor inválido");
	}

	private static String parameterName(@Nullable String name) {
		return (name != null) ? name : "desconhecido";
	}

	private static String path(WebRequest request) {
		return (request instanceof ServletWebRequest servlet) ? servlet.getRequest().getRequestURI() : "";
	}

}
