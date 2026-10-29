package academy.jairo.springdesafiotecnico.activation.application;

import java.time.Clock;
import java.util.UUID;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestRepository;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestSpecifications;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivationRequestService {

	// Ordenação fixa, atendida pelos índices (customer_id|status, created_at DESC); id desempata.
	private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

	private final ActivationRequestRepository repository;

	private final Clock clock;

	public ActivationRequestService(ActivationRequestRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional
	public ActivationRequest create(String externalId, String customerId, String productCode) {
		return repository.save(ActivationRequest.create(externalId, customerId, productCode, clock.instant()));
	}

	@Transactional(readOnly = true)
	public ActivationRequest findById(UUID id) {
		return repository.findById(id).orElseThrow(() -> new ActivationRequestNotFoundException(id));
	}

	@Transactional(readOnly = true)
	public Page<ActivationRequest> findAll(@Nullable ActivationStatus status, @Nullable String customerId, int page,
			int size) {
		return repository.findAll(ActivationRequestSpecifications.filter(status, customerId),
				PageRequest.of(page, size, NEWEST_FIRST));
	}

}
