package academy.jairo.springdesafiotecnico.activation.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Liga o {@link ActivationDispatcher} ao relógio. {@code fixedDelay} evita rodadas sobrepostas na mesma
 * instância; entre instâncias, o {@code SKIP LOCKED} divide o trabalho. Os padrões repetem os de
 * {@link ActivationDispatchProperties}. {@code activation.dispatch.enabled=false} desliga o envio.
 */
@Component
@EnableScheduling
@ConditionalOnBooleanProperty(name = "activation.dispatch.enabled", matchIfMissing = true)
class ActivationDispatchScheduler {

	private final ActivationDispatcher dispatcher;

	ActivationDispatchScheduler(ActivationDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@Scheduled(fixedDelayString = "${activation.dispatch.interval:1s}")
	void dispatchDue() {
		dispatcher.dispatchDue();
	}

	@Scheduled(fixedDelayString = "${activation.dispatch.reconciliation.interval:30s}")
	void reconcileStale() {
		dispatcher.reconcileStale();
	}

}
