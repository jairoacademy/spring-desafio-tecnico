package academy.jairo.springdesafiotecnico.activation.infrastructure.provider;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/** Desfecho de uma chamada ao provedor, já classificado; o resto da aplicação não vê exceções HTTP. */
public sealed interface ProviderOutcome {

	/** {@code 2xx}: ativação aceita. */
	record Accepted() implements ProviderOutcome {
	}

	/** {@code 4xx} definitivo: repetir o mesmo pedido não muda a resposta. */
	record Rejected(int status) implements ProviderOutcome {
	}

	/**
	 * Falha temporária. {@code retryAt} é o instante pedido pelo provedor em {@code Retry-After};
	 * nulo, vale o backoff.
	 */
	record TemporaryFailure(@Nullable Instant retryAt) implements ProviderOutcome {
	}

}
