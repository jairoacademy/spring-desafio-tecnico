package academy.jairo.springdesafiotecnico.activation.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ActivationRequestRepository
		extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

	// Comparação exata (diferencia caixa), atendida pelo índice da constraint uq_activation_request_external_id.
	Optional<ActivationRequest> findByExternalId(String externalId);

	// Consulta da API: id de outro dono fica igual a id inexistente (BOLA, ADR 0004).
	Optional<ActivationRequest> findByIdAndClientId(UUID id, String clientId);

	/**
	 * Reserva do worker: {@code PENDING} vencidas, bloqueadas até o fim da transação. {@code SKIP LOCKED}
	 * pula as linhas já bloqueadas por outra execução, sem esperar. Atendida por ix_activation_request_pending_due.
	 */
	@Query(value = """
			SELECT * FROM activation_request
			 WHERE status = 'PENDING' AND next_attempt_at <= :now
			 ORDER BY next_attempt_at
			 LIMIT :batchSize
			   FOR UPDATE SKIP LOCKED""", nativeQuery = true)
	List<ActivationRequest> lockDuePending(Instant now, int batchSize);

	/**
	 * Reconciliação: {@code PROCESSING} sem mudança desde {@code cutoff}, com o mesmo bloqueio da reserva.
	 * Atendida por ix_activation_request_processing_updated.
	 */
	@Query(value = """
			SELECT * FROM activation_request
			 WHERE status = 'PROCESSING' AND updated_at < :cutoff
			 LIMIT :batchSize
			   FOR UPDATE SKIP LOCKED""", nativeQuery = true)
	List<ActivationRequest> lockStaleProcessing(Instant cutoff, int batchSize);

}
