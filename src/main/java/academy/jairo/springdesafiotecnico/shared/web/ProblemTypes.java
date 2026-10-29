package academy.jairo.springdesafiotecnico.shared.web;

import java.net.URI;

/** Identificadores {@code type} dos ProblemDetail da API ({@code .example} é TLD reservado: não é uma página). */
final class ProblemTypes {

	private static final String BASE = "https://problems.ativacao.example/";

	static final URI VALIDATION_ERROR = URI.create(BASE + "validation-error");

	static final URI INVALID_PARAMETER = URI.create(BASE + "invalid-parameter");

	static final URI MALFORMED_BODY = URI.create(BASE + "malformed-body");

	static final URI NOT_FOUND = URI.create(BASE + "not-found");

	static final URI INTERNAL_ERROR = URI.create(BASE + "internal-error");

	static final URI ABOUT_BLANK = URI.create("about:blank");

	private ProblemTypes() {
	}

}
