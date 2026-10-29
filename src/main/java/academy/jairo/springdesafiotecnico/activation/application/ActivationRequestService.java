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

	// Ordenação fixa, atendida pelos índices (client_id[, customer_id|status], created_at DESC); id desempata.
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
	public CreationResult create(String clientId, String externalId, String customerId, String productCode) {
		Optional<ActivationRequest> existing = repository.findByExternalId(externalId);
		if (existing.isPresent()) {
			return resolveExisting(existing.get(), clientId, customerId, productCode);
		}
		try {
			ActivationRequest saved = repository
				.saveAndFlush(ActivationRequest.create(clientId, externalId, customerId, productCode, clock.instant()));
			return new CreationResult(saved, true);
		}
		catch (DataIntegrityViolationException ex) {
			// corrida com outra criação do mesmo externalId; sem registro, a violação foi outra
			ActivationRequest winner = repository.findByExternalId(externalId).orElseThrow(() -> ex);
			return resolveExisting(winner, clientId, customerId, productCode);
		}
	}

	/**
	 * Dono antes do conteúdo: o registro de outro cliente é sempre conflito sem detalhes. Devolver o registro
	 * vazaria id e status, e os campos divergentes permitiriam descobrir o conteúdo por tentativa (ADR 0004).
	 */
	private static CreationResult resolveExisting(ActivationRequest existing, String clientId, String customerId,
			String productCode) {
		if (!existing.getClientId().equals(clientId)) {
			throw ActivationRequestConflictException.ownedByAnotherClient(existing.getExternalId());
		}
		List<String> conflictingFields = existing.conflictingFields(customerId, productCode);
		if (!conflictingFields.isEmpty()) {
			throw new ActivationRequestConflictException(existing.getExternalId(), conflictingFields);
		}
		return new CreationResult(existing, false);
	}

	/** Solicitação de outro dono responde como inexistente: mesma exceção, mesma mensagem. */
	@Transactional(readOnly = true)
	public ActivationRequest findById(String clientId, UUID id) {
		return repository.findByIdAndClientId(id, clientId)
			.orElseThrow(() -> new ActivationRequestNotFoundException(id));
	}

	@Transactional(readOnly = true)
	public Page<ActivationRequest> findAll(String clientId, @Nullable ActivationStatus status,
			@Nullable String customerId, int page, int size) {
		return repository.findAll(ActivationRequestSpecifications.filter(clientId, status, customerId),
				PageRequest.of(page, size, NEWEST_FIRST));
	}

}
