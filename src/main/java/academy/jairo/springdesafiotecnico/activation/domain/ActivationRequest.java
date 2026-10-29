package academy.jairo.springdesafiotecnico.activation.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.jspecify.annotations.Nullable;

/**
 * Solicitação de ativação. Mudanças de status só pelos métodos de transição, que
 * consultam {@link ActivationStatus#canTransitionTo} antes de alterar qualquer campo.
 */
@Entity
@Table(name = "activation_request")
public class ActivationRequest {

	@Id
	private UUID id;

	@Column(name = "external_id", nullable = false, length = 64)
	private String externalId;

	@Column(name = "customer_id", nullable = false, length = 64)
	private String customerId;

	@Column(name = "product_code", nullable = false, length = 50)
	private String productCode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ActivationStatus status;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(nullable = false)
	private @Nullable Long version;

	protected ActivationRequest() {
		// exigido pelo JPA
	}

	private ActivationRequest(String externalId, String customerId, String productCode, Instant now) {
		this.id = UUID.randomUUID();
		this.externalId = externalId;
		this.customerId = customerId;
		this.productCode = productCode;
		this.status = ActivationStatus.PENDING;
		this.attempts = 0;
		this.nextAttemptAt = now;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static ActivationRequest create(String externalId, String customerId, String productCode, Instant now) {
		return new ActivationRequest(externalId, customerId, productCode, truncate(now));
	}

	public void startProcessing(Instant now) {
		transitionTo(ActivationStatus.PROCESSING, now);
	}

	public void complete(Instant now) {
		transitionTo(ActivationStatus.COMPLETED, now);
	}

	public void fail(Instant now) {
		transitionTo(ActivationStatus.FAILED, now);
	}

	/** Falha temporária: volta para {@code PENDING} com nova tentativa agendada. */
	public void scheduleRetry(Instant nextAttemptAt, Instant now) {
		transitionTo(ActivationStatus.PENDING, now);
		this.attempts++;
		this.nextAttemptAt = truncate(nextAttemptAt);
	}

	private void transitionTo(ActivationStatus target, Instant now) {
		if (!status.canTransitionTo(target)) {
			throw new InvalidStatusTransitionException(status, target);
		}
		this.status = target;
		this.updatedAt = truncate(now);
	}

	// timestamptz do PostgreSQL guarda microssegundos; truncar mantém o valor em memória igual ao lido do banco
	private static Instant truncate(Instant instant) {
		return instant.truncatedTo(ChronoUnit.MICROS);
	}

	public UUID getId() {
		return id;
	}

	public String getExternalId() {
		return externalId;
	}

	public String getCustomerId() {
		return customerId;
	}

	public String getProductCode() {
		return productCode;
	}

	public ActivationStatus getStatus() {
		return status;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public @Nullable Long getVersion() {
		return version;
	}

}
