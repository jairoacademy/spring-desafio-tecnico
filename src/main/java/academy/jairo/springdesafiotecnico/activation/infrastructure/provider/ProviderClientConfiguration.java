package academy.jairo.springdesafiotecnico.activation.infrastructure.provider;

import java.net.http.HttpClient;

import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "provider", types = ProviderClient.class)
class ProviderClientConfiguration {

	/**
	 * O {@code HttpClient} do JDK usa HTTP/2 por padrão e, em {@code http://}, tenta o upgrade h2c, que
	 * servidores HTTP/1.1 (como o WireMock) não aceitam e encerram a conexão. HTTP/1.1 fixo evita o upgrade.
	 */
	@Bean
	ClientHttpRequestFactoryBuilderCustomizer<JdkClientHttpRequestFactoryBuilder> http11RequestFactoryCustomizer() {
		return builder -> builder.withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1));
	}

}
