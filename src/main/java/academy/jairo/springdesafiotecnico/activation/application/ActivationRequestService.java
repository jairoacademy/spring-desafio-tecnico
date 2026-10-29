package academy.jairo.springdesafiotecnico.activation.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestRepository;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestSpecifications;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
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

	/**
	 * Cria a solicitação ou devolve a existente com o mesmo {@code externalId} e o mesmo conteúdo.
	 * A constraint única do banco é a garantia; a busca prévia só evita a violação no retry sequencial.
	 * Sem {@code @Transactional}: depois da violação a transação do PostgreSQL fica abortada, então o
	 * insert e a releitura rodam cada um na transação do repositório.
	 */
	public CreationResult create(String externalId, String customerId, String productCode) {
		Optional<ActivationRequest> existing = repository.findByExternalId(externalId);
		if (existing.isPresent()) {
			return resolveExisting(existing.get(), customerId, productCode);
		}
		try {
			ActivationRequest saved = repository
				.saveAndFlush(ActivationRequest.create(externalId, customerId, productCode, clock.instant()));
			return new CreationResult(saved, true);
		}
		catch (DataIntegrityViolationException ex) {
			// corrida com outra criação do mesmo externalId; sem registro, a violação foi outra
			ActivationRequest winner = repository.findByExternalId(externalId).orElseThrow(() -> ex);
			return resolveExisting(winner, customerId, productCode);
		}
	}

	private static CreationResult resolveExisting(ActivationRequest existing, String customerId, String productCode) {
		List<String> conflictingFields = existing.conflictingFields(customerId, productCode);
		if (!conflictingFields.isEmpty()) {
			throw new ActivationRequestConflictException(existing.getExternalId(), conflictingFields);
		}
		return new CreationResult(existing, false);
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
