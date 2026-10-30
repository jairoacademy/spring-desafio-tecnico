package academy.jairo.springdesafiotecnico.activation.application;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestRepository;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestSpecifications;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
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

	static final String TRACEPARENT = "traceparent";

	private final ActivationRequestRepository repository;

	private final Clock clock;

	private final Tracer tracer;

	private final Propagator propagator;

	private final ActivationMetrics metrics;

	public ActivationRequestService(ActivationRequestRepository repository, Clock clock, Tracer tracer,
			Propagator propagator, ActivationMetrics metrics) {
		this.repository = repository;
		this.clock = clock;
		this.tracer = tracer;
		this.propagator = propagator;
		this.metrics = metrics;
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
			ActivationRequest saved = repository.saveAndFlush(ActivationRequest.create(clientId, externalId,
					customerId, productCode, clock.instant(), currentTraceParent()));
			metrics.transitionedTo(ActivationStatus.PENDING);
			return new CreationResult(saved, true);
		}
		catch (DataIntegrityViolationException ex) {
			// corrida com outra criação do mesmo externalId; sem registro, a violação foi outra
			ActivationRequest winner = repository.findByExternalId(externalId).orElseThrow(() -> ex);
			return resolveExisting(winner, clientId, customerId, productCode);
		}
	}

	/**
	 * {@code traceparent} do span atual, gravado só em registro novo: o retry idempotente não o altera.
	 * Sem span (tracing desligado), não há contexto a guardar.
	 */
	private @Nullable String currentTraceParent() {
		Span span = tracer.currentSpan();
		if (span == null) {
			return null;
		}
		Map<String, String> carrier = new HashMap<>();
		propagator.inject(span.context(), carrier, Map::put);
		return carrier.get(TRACEPARENT);
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
