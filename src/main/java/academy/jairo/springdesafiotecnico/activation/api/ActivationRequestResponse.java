package academy.jairo.springdesafiotecnico.activation.api;

import java.time.Instant;
import java.util.UUID;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus;

public record ActivationRequestResponse(UUID id, String externalId, String customerId, String productCode,
		ActivationStatus status, Instant createdAt, Instant updatedAt) {

	static ActivationRequestResponse from(ActivationRequest request) {
		return new ActivationRequestResponse(request.getId(), request.getExternalId(), request.getCustomerId(),
				request.getProductCode(), request.getStatus(), request.getCreatedAt(), request.getUpdatedAt());
	}

}
