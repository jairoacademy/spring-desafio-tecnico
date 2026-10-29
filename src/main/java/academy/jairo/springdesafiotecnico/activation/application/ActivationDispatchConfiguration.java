package academy.jairo.springdesafiotecnico.activation.application;

import java.util.Random;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ActivationDispatchProperties.class)
class ActivationDispatchConfiguration {

	// Random é thread-safe: o worker e a reconciliação podem sortear em threads diferentes
	@Bean
	RetryPolicy retryPolicy(ActivationDispatchProperties properties) {
		return new RetryPolicy(properties.backoff().base(), properties.backoff().max(), new Random());
	}

}
