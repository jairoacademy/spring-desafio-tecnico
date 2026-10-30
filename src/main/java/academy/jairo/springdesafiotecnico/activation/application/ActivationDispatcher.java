package academy.jairo.springdesafiotecnico.activation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus;
import academy.jairo.springdesafiotecnico.activation.domain.FailureReason;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestRepository;
import academy.jairo.springdesafiotecnico.activation.infrastructure.provider.ProviderGateway;
import academy.jairo.springdesafiotecnico.activation.infrastructure.provider.ProviderOutcome;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Envia as solicitações {@code PENDING} vencidas ao provedor (design.md, decisões 3, 4 e 9). Cada ciclo
 * reserva um lote em uma transação curta, chama o provedor fora de transação e grava o resultado de cada
 * item em sua própria transação.
 */
@Service
public class ActivationDispatcher {

	private static final Logger log = LoggerFactory.getLogger(ActivationDispatcher.class);

	private final ActivationRequestRepository repository;

	private final ProviderGateway gateway;

	private final RetryPolicy retryPolicy;

	private final ActivationDispatchProperties properties;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final Tracer tracer;

	private final Propagator propagator;

	private final ActivationMetrics metrics;

	public ActivationDispatcher(ActivationRequestRepository repository, ProviderGateway gateway,
			RetryPolicy retryPolicy, ActivationDispatchProperties properties,
			PlatformTransactionManager transactionManager, Clock clock, Tracer tracer, Propagator propagator,
			ActivationMetrics metrics) {
		this.repository = repository;
		this.gateway = gateway;
		this.retryPolicy = retryPolicy;
		this.properties = properties;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.tracer = tracer;
		this.propagator = propagator;
		this.metrics = metrics;
	}

	/** Um ciclo do worker. Devolve quantas solicitações foram reservadas. */
	public int dispatchDue() {
		List<ClaimedRequest> claimed = claimDue();
		for (ClaimedRequest request : claimed) {
			Span span = startDispatchSpan(request);
			try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
				ProviderOutcome outcome = gateway.activate(request.id(), request.externalId(), request.customerId(),
						request.productCode());
				applyOutcome(request, outcome);
			}
			finally {
				span.end();
			}
		}
		return claimed.size();
	}

	/**
	 * Span do envio, filho do contexto gravado na criação; sem contexto ou com valor inválido, abre um trace
	 * novo. Em escopo, o MDC e o header {@code traceparent} ao provedor levam o {@code traceId} do span.
	 */
	private Span startDispatchSpan(ClaimedRequest request) {
		String traceParent = request.traceParent();
		Span.Builder builder = traceParent != null
				? propagator.extract(Map.of(ActivationRequestService.TRACEPARENT, traceParent), Map::get)
				: tracer.spanBuilder();
		return builder.name("activation.dispatch").tag("activation.id", request.id().toString()).start();
	}

	/**
	 * Devolve para {@code PENDING} (ou {@code FAILED}, no limite) as solicitações em {@code PROCESSING} há
	 * mais que {@code stale-after}. O desfecho da chamada é desconhecido, por isso conta como tentativa.
	 */
	public int reconcileStale() {
		List<ActivationStatus> reconciled = transaction.execute(status -> {
			Instant now = clock.instant();
			List<ActivationRequest> stale = repository
				.lockStaleProcessing(now.minus(properties.reconciliation().staleAfter()), properties.batchSize());
			for (ActivationRequest request : stale) {
				log.warn("Solicitação {} presa em PROCESSING desde {}; contada como falha temporária",
						request.getId(), request.getUpdatedAt());
				registerTemporaryFailure(request, null, now);
			}
			return stale.stream().map(ActivationRequest::getStatus).toList();
		});
		Objects.requireNonNull(reconciled).forEach(metrics::transitionedTo);
		return reconciled.size();
	}

	/** Passo 1: reserva o lote e grava {@code PROCESSING} antes de qualquer chamada ao provedor. */
	List<ClaimedRequest> claimDue() {
		List<ClaimedRequest> claimed = transaction.execute(status -> {
			Instant now = clock.instant();
			List<ActivationRequest> due = repository.lockDuePending(now, properties.batchSize());
			due.forEach(request -> request.startProcessing(now));
			// flush para ler a versão já incrementada pela reserva
			return repository.saveAllAndFlush(due)
				.stream()
				.map(request -> new ClaimedRequest(request.getId(), Objects.requireNonNull(request.getVersion()),
						request.getExternalId(), request.getCustomerId(), request.getProductCode(),
						request.getTraceParent()))
				.toList();
		});
		Objects.requireNonNull(claimed).forEach(request -> metrics.transitionedTo(ActivationStatus.PROCESSING));
		return claimed;
	}

	/**
	 * Passo 3: aplica o desfecho se a solicitação ainda estiver na versão da reserva. Outra versão significa
	 * que a reconciliação já a devolveu; o resultado tardio é descartado e o reenvio usa a mesma chave.
	 */
	void applyOutcome(ClaimedRequest claimed, ProviderOutcome outcome) {
		try {
			ActivationStatus applied = transaction.execute(status -> {
				ActivationRequest request = repository.findById(claimed.id()).orElse(null);
				if (request == null || !Objects.equals(request.getVersion(), claimed.version())) {
					log.info("Resultado tardio da solicitação {} descartado: {}", claimed.id(), outcome);
					return null;
				}
				Instant now = clock.instant();
				switch (outcome) {
					case ProviderOutcome.Accepted accepted -> request.complete(now);
					case ProviderOutcome.Rejected rejected -> request.fail(FailureReason.PROVIDER_REJECTED, now);
					case ProviderOutcome.TemporaryFailure failure ->
						registerTemporaryFailure(request, failure.retryAt(), now);
				}
				repository.saveAndFlush(request);
				return request.getStatus();
			});
			// resultado tardio descartado não é transição
			if (applied != null) {
				metrics.transitionedTo(applied);
			}
		}
		catch (ObjectOptimisticLockingFailureException ex) {
			log.info("Resultado tardio da solicitação {} descartado: gravação concorrente", claimed.id());
		}
		catch (RuntimeException ex) {
			// a solicitação continua PROCESSING e a reconciliação a devolve depois
			log.error("Falha ao gravar o resultado da solicitação {}", claimed.id(), ex);
		}
	}

	private void registerTemporaryFailure(ActivationRequest request, @Nullable Instant retryAt, Instant now) {
		Instant nextAttemptAt = retryAt != null ? retryAt : now.plus(retryPolicy.delayFor(request.getAttempts() + 1));
		request.registerTemporaryFailure(nextAttemptAt, properties.maxAttempts(), now);
		if (request.getFailureReason() == FailureReason.MAX_ATTEMPTS_EXCEEDED) {
			log.warn("Solicitação {} falhou: limite de {} tentativas atingido", request.getId(),
					properties.maxAttempts());
		}
	}

}
