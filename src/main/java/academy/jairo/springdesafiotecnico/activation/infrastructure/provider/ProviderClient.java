package academy.jairo.springdesafiotecnico.activation.infrastructure.provider;

import java.util.UUID;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** Provedor externo de ativação; base URL e timeouts em {@code spring.http.serviceclient.provider.*}. */
@HttpExchange("/provider/v1")
interface ProviderClient {

	@PostExchange("/activate")
	void activate(@RequestHeader("Idempotency-Key") UUID idempotencyKey, @RequestBody ActivationPayload payload);

}
