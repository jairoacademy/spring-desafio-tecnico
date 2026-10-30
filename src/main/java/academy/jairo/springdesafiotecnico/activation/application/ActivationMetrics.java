package academy.jairo.springdesafiotecnico.activation.application;

import java.util.EnumMap;
import java.util.Map;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Contador de transições para o status, rótulo {@code status} com cardinalidade fixa (design.md, decisão 6).
 * Os quatro status são registrados na subida para a série existir antes do primeiro evento. Quem chama
 * incrementa só depois do commit.
 */
@Component
public class ActivationMetrics {

	static final String TRANSITIONS = "activation.requests.transitions";

	private final Map<ActivationStatus, Counter> transitions = new EnumMap<>(ActivationStatus.class);

	public ActivationMetrics(MeterRegistry registry) {
		for (ActivationStatus status : ActivationStatus.values()) {
			transitions.put(status, Counter.builder(TRANSITIONS)
				.description("Transições de solicitações de ativação, por status de destino")
				.tag("status", status.name())
				.register(registry));
		}
	}

	public void transitionedTo(ActivationStatus status) {
		transitions.get(status).increment();
	}

}
