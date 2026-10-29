package academy.jairo.springdesafiotecnico.activation.application;

import java.util.UUID;

/** Solicitação reservada pelo worker; {@code version} é a versão gravada na reserva. */
record ClaimedRequest(UUID id, long version, String externalId, String customerId, String productCode) {
}
