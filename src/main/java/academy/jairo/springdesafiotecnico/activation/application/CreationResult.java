package academy.jairo.springdesafiotecnico.activation.application;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;

/** Resultado da criação: {@code created} falso quando o {@code externalId} já estava registrado (retry idêntico). */
public record CreationResult(ActivationRequest request, boolean created) {

}
