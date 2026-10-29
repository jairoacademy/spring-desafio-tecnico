package academy.jairo.springdesafiotecnico.activation.application;

import java.util.List;

/**
 * Mesmo {@code externalId} de uma solicitação existente: do mesmo dono com conteúdo diferente, ou de outro dono.
 * No segundo caso, {@code conflictingFields} fica vazia, para não revelar o conteúdo da solicitação alheia.
 */
public class ActivationRequestConflictException extends RuntimeException {

	private final String externalId;

	private final List<String> conflictingFields;

	public ActivationRequestConflictException(String externalId, List<String> conflictingFields) {
		super(conflictingFields.isEmpty() ? "externalId " + externalId + " já registrado por outro cliente"
				: "Solicitação com externalId " + externalId + " diverge em " + conflictingFields);
		this.externalId = externalId;
		this.conflictingFields = List.copyOf(conflictingFields);
	}

	/** {@code externalId} registrado por outro cliente, qualquer que seja o conteúdo. */
	public static ActivationRequestConflictException ownedByAnotherClient(String externalId) {
		return new ActivationRequestConflictException(externalId, List.of());
	}

	public String getExternalId() {
		return externalId;
	}

	public List<String> getConflictingFields() {
		return conflictingFields;
	}

}
