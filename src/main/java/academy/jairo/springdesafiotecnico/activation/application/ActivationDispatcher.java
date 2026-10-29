package academy.jairo.springdesafiotecnico.activation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.domain.FailureReason;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestRepository;
import academy.jairo.springdesafiotecnico.activation.infrastructure.provider.ProviderGateway;
import academy.jairo.springdesafiotecnico.activation.infrastructure.provider.ProviderOutcome;
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

	public ActivationDispatcher(ActivationRequestRepository repository, ProviderGateway gateway,
			RetryPolicy retryPolicy, ActivationDispatchProperties properties,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.repository = repository;
		this.gateway = gateway;
		this.retryPolicy = retryPolicy;
		this.properties = properties;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/** Um ciclo do worker. Devolve quantas solicitações foram reservadas. */
	public int dispatchDue() {
		List<ClaimedRequest> claimed = claimDue();
		for (ClaimedRequest request : claimed) {
			ProviderOutcome outcome = gateway.activate(request.id(), request.externalId(), request.customerId(),
					request.productCode());
			applyOutcome(request, outcome);
		}
		return claimed.size();
	}

	/**
	 * Devolve para {@code PENDING} (ou {@code FAILED}, no limite) as solicitações em {@code PROCESSING} há
	 * mais que {@code stale-after}. O desfecho da chamada é desconhecido, por isso conta como tentativa.
	 */
	public int reconcileStale() {
		Integer reconciled = transaction.execute(status -> {
			Instant now = clock.instant();
			List<ActivationRequest> stale = repository
				.lockStaleProcessing(now.minus(properties.reconciliation().staleAfter()), properties.batchSize());
			for (ActivationRequest request : stale) {
				log.warn("Solicitação {} presa em PROCESSING desde {}; contada como falha temporária",
						request.getId(), request.getUpdatedAt());
				registerTemporaryFailure(request, null, now);
			}
			return stale.size();
		});
		return Objects.requireNonNull(reconciled);
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
						request.getExternalId(), request.getCustomerId(), request.getProductCode()))
				.toList();
		});
		return Objects.requireNonNull(claimed);
	}

	/**
	 * Passo 3: aplica o desfecho se a solicitação ainda estiver na versão da reserva. Outra versão significa
	 * que a reconciliação já a devolveu; o resultado tardio é descartado e o reenvio usa a mesma chave.
	 */
	void applyOutcome(ClaimedRequest claimed, ProviderOutcome outcome) {
		try {
			transaction.executeWithoutResult(status -> {
				ActivationRequest request = repository.findById(claimed.id()).orElse(null);
				if (request == null || !Objects.equals(request.getVersion(), claimed.version())) {
					log.info("Resultado tardio da solicitação {} descartado: {}", claimed.id(), outcome);
					return;
				}
				Instant now = clock.instant();
				switch (outcome) {
					case ProviderOutcome.Accepted accepted -> request.complete(now);
					case ProviderOutcome.Rejected rejected -> request.fail(FailureReason.PROVIDER_REJECTED, now);
					case ProviderOutcome.TemporaryFailure failure ->
						registerTemporaryFailure(request, failure.retryAt(), now);
				}
				repository.saveAndFlush(request);
			});
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
