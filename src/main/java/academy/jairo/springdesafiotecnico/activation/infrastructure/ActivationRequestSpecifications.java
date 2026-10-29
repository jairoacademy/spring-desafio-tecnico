package academy.jairo.springdesafiotecnico.activation.infrastructure;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/** Filtros da listagem: o dono sempre restringe; nos opcionais, filtro nulo não restringe. */
public final class ActivationRequestSpecifications {

	private ActivationRequestSpecifications() {
	}

	public static Specification<ActivationRequest> filter(String clientId, @Nullable ActivationStatus status,
			@Nullable String customerId) {
		Specification<ActivationRequest> spec = hasClientId(clientId);
		if (status != null) {
			spec = spec.and(hasStatus(status));
		}
		if (customerId != null) {
			spec = spec.and(hasCustomerId(customerId));
		}
		return spec;
	}

	static Specification<ActivationRequest> hasClientId(String clientId) {
		return (root, query, cb) -> cb.equal(root.get("clientId"), clientId);
	}

	static Specification<ActivationRequest> hasStatus(ActivationStatus status) {
		return (root, query, cb) -> cb.equal(root.get("status"), status);
	}

	static Specification<ActivationRequest> hasCustomerId(String customerId) {
		return (root, query, cb) -> cb.equal(root.get("customerId"), customerId);
	}

}
