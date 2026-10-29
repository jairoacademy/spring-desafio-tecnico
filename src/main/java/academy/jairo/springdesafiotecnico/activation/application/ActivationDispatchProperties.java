package academy.jairo.springdesafiotecnico.activation.application;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Envio assíncrono ao provedor. {@code reconciliation.stale-after} precisa ser maior que
 * connect timeout + read timeout do provedor, senão a reconciliação disputa com chamadas em andamento.
 */
@Validated
@ConfigurationProperties("activation.dispatch")
public record ActivationDispatchProperties(@DefaultValue("true") boolean enabled,
		@DefaultValue("1s") @DurationMin(millis = 1) Duration interval, @DefaultValue("10") @Positive int batchSize,
		@DefaultValue("5") @Positive int maxAttempts, @DefaultValue @Valid Backoff backoff,
		@DefaultValue @Valid Reconciliation reconciliation) {

	public record Backoff(@DefaultValue("2s") @DurationMin(millis = 1) Duration base,
			@DefaultValue("5m") @DurationMin(millis = 1) Duration max) {
	}

	public record Reconciliation(@DefaultValue("30s") @DurationMin(millis = 1) Duration interval,
			@DefaultValue("2m") @DurationMin(millis = 1) Duration staleAfter) {
	}

}
