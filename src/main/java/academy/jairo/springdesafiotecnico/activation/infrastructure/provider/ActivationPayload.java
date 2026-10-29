package academy.jairo.springdesafiotecnico.activation.infrastructure.provider;

/** Corpo do {@code POST /provider/v1/activate}; contrato suposto, o enunciado não o define. */
record ActivationPayload(String externalId, String customerId, String productCode) {
}
