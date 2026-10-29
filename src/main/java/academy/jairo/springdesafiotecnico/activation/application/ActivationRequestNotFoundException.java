package academy.jairo.springdesafiotecnico.activation.application;

import java.util.UUID;

public class ActivationRequestNotFoundException extends RuntimeException {

	private final UUID id;

	public ActivationRequestNotFoundException(UUID id) {
		super("Solicitação não encontrada: " + id);
		this.id = id;
	}

	public UUID getId() {
		return id;
	}

}
