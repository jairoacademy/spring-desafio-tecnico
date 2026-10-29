package academy.jairo.springdesafiotecnico.activation.api;

import java.time.Instant;
import java.util.UUID;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus;
import academy.jairo.springdesafiotecnico.activation.domain.FailureReason;
import org.jspecify.annotations.Nullable;

public record ActivationRequestResponse(UUID id, String externalId, String customerId, String productCode,
		ActivationStatus status, @Nullable FailureReason failureReason, Instant createdAt, Instant updatedAt) {

	static ActivationRequestResponse from(ActivationRequest request) {
		return new ActivationRequestResponse(request.getId(), request.getExternalId(), request.getCustomerId(),
				request.getProductCode(), request.getStatus(), request.getFailureReason(), request.getCreatedAt(),
				request.getUpdatedAt());
	}

}
