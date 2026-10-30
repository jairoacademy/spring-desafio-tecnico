package academy.jairo.springdesafiotecnico.activation.application;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Solicitação reservada pelo worker; {@code version} é a versão gravada na reserva e {@code traceParent} o
 * contexto de trace da criação, se houver.
 */
record ClaimedRequest(UUID id, long version, String externalId, String customerId, String productCode,
		@Nullable String traceParent) {
}
