package academy.jairo.springdesafiotecnico.activation.domain;

/** Motivo de uma solicitação {@code FAILED}. */
public enum FailureReason {

	/** O provedor recusou o pedido ({@code 4xx} definitivo). */
	PROVIDER_REJECTED,

	/** Falha temporária na última tentativa permitida. */
	MAX_ATTEMPTS_EXCEEDED

}
