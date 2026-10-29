package academy.jairo.springdesafiotecnico.activation.application;

import java.util.List;

/** Mesmo {@code externalId} de uma solicitação existente, com conteúdo diferente. */
public class ActivationRequestConflictException extends RuntimeException {

	private final String externalId;

	private final List<String> conflictingFields;

	public ActivationRequestConflictException(String externalId, List<String> conflictingFields) {
		super("Solicitação com externalId " + externalId + " diverge em " + conflictingFields);
		this.externalId = externalId;
		this.conflictingFields = List.copyOf(conflictingFields);
	}

	public String getExternalId() {
		return externalId;
	}

	public List<String> getConflictingFields() {
		return conflictingFields;
	}

}
