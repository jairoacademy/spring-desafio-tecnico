package academy.jairo.springdesafiotecnico.activation.application;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationMetricsTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final ActivationMetrics metrics = new ActivationMetrics(registry);

	@Test
	void registersAllStatusesAtZero() {
		for (ActivationStatus status : ActivationStatus.values()) {
			assertThat(count(status)).isZero();
		}
	}

	@Test
	void countsOnlyTheTargetStatus() {
		metrics.transitionedTo(ActivationStatus.COMPLETED);
		metrics.transitionedTo(ActivationStatus.COMPLETED);
		metrics.transitionedTo(ActivationStatus.FAILED);

		assertThat(count(ActivationStatus.COMPLETED)).isEqualTo(2);
		assertThat(count(ActivationStatus.FAILED)).isEqualTo(1);
		assertThat(count(ActivationStatus.PENDING)).isZero();
		assertThat(count(ActivationStatus.PROCESSING)).isZero();
	}

	private double count(ActivationStatus status) {
		return registry.get(ActivationMetrics.TRANSITIONS).tag("status", status.name()).counter().count();
	}

}
