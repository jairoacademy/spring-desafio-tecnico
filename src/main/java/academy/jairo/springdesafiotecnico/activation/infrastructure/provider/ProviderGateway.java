package academy.jairo.springdesafiotecnico.activation.infrastructure.provider;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Chama o provedor e classifica o retorno em {@link ProviderOutcome} (design.md, decisão 6). */
@Component
public class ProviderGateway {

	private static final Logger log = LoggerFactory.getLogger(ProviderGateway.class);

	private final ProviderClient client;

	private final Clock clock;

	ProviderGateway(ProviderClient client, Clock clock) {
		this.client = client;
		this.clock = clock;
	}

	/** Envia a ativação com {@code Idempotency-Key} igual ao {@code id} da solicitação. */
	public ProviderOutcome activate(UUID id, String externalId, String customerId, String productCode) {
		try {
			client.activate(id, new ActivationPayload(externalId, customerId, productCode));
			return new ProviderOutcome.Accepted();
		}
		catch (RestClientResponseException ex) {
			return classify(id, ex);
		}
		catch (ResourceAccessException ex) {
			// timeout, conexão recusada ou encerrada sem resposta
			log.warn("Falha de I/O ao chamar o provedor para a solicitação {}: {}", id, ex.getMessage());
			return new ProviderOutcome.TemporaryFailure(null);
		}
		catch (RuntimeException ex) {
			log.error("Erro inesperado ao chamar o provedor para a solicitação {}", id, ex);
			return new ProviderOutcome.TemporaryFailure(null);
		}
	}

	private ProviderOutcome classify(UUID id, RestClientResponseException ex) {
		int status = ex.getStatusCode().value();
		if (status == HttpStatus.TOO_MANY_REQUESTS.value() || status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
			Instant retryAt = retryAfter(ex.getResponseHeaders());
			log.warn("Provedor respondeu {} para a solicitação {}; Retry-After: {}", status, id, retryAt);
			return new ProviderOutcome.TemporaryFailure(retryAt);
		}
		if (ex.getStatusCode().is4xxClientError() && status != HttpStatus.REQUEST_TIMEOUT.value()) {
			log.warn("Provedor recusou a solicitação {} com status {}", id, status);
			return new ProviderOutcome.Rejected(status);
		}
		log.warn("Provedor respondeu {} para a solicitação {}", status, id);
		return new ProviderOutcome.TemporaryFailure(null);
	}

	/** {@code Retry-After} em segundos ou em data HTTP; inválido vale nulo, data no passado vale agora. */
	private @Nullable Instant retryAfter(@Nullable HttpHeaders headers) {
		String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (value == null) {
			return null;
		}
		Instant now = clock.instant();
		String trimmed = value.trim();
		if (!trimmed.isEmpty() && trimmed.chars().allMatch(Character::isDigit)) {
			try {
				return now.plusSeconds(Long.parseLong(trimmed));
			}
			catch (NumberFormatException | ArithmeticException | DateTimeException ex) {
				return null;
			}
		}
		try {
			Instant date = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
			return date.isBefore(now) ? now : date;
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

}
