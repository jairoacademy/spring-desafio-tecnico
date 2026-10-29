package academy.jairo.springdesafiotecnico.activation.application;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationDispatchPropertiesTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
		.withUserConfiguration(ActivationDispatchConfiguration.class);

	@Test
	void usesDefaults() {
		contextRunner.run(context -> {
			ActivationDispatchProperties properties = context.getBean(ActivationDispatchProperties.class);
			assertThat(properties.enabled()).isTrue();
			assertThat(properties.interval()).isEqualTo(Duration.ofSeconds(1));
			assertThat(properties.batchSize()).isEqualTo(10);
			assertThat(properties.maxAttempts()).isEqualTo(5);
			assertThat(properties.backoff().base()).isEqualTo(Duration.ofSeconds(2));
			assertThat(properties.backoff().max()).isEqualTo(Duration.ofMinutes(5));
			assertThat(properties.reconciliation().interval()).isEqualTo(Duration.ofSeconds(30));
			assertThat(properties.reconciliation().staleAfter()).isEqualTo(Duration.ofMinutes(2));
		});
	}

	@Test
	void rejectsInvalidValues() {
		contextRunner.withPropertyValues("activation.dispatch.batch-size=0")
			.run(context -> assertThat(context).hasFailed());
		contextRunner.withPropertyValues("activation.dispatch.backoff.base=0s")
			.run(context -> assertThat(context).hasFailed());
	}

}
