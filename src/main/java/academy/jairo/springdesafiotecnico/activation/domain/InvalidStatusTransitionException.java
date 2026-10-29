package academy.jairo.springdesafiotecnico.activation.domain;

public class InvalidStatusTransitionException extends RuntimeException {

	private final ActivationStatus from;

	private final ActivationStatus to;

	public InvalidStatusTransitionException(ActivationStatus from, ActivationStatus to) {
		super("Transição de status não permitida: " + from + " -> " + to);
		this.from = from;
		this.to = to;
	}

	public ActivationStatus getFrom() {
		return from;
	}

	public ActivationStatus getTo() {
		return to;
	}

}
