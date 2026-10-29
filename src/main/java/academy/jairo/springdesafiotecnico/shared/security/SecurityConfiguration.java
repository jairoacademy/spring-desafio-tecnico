package academy.jairo.springdesafiotecnico.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.web.SecurityFilterChain;

/**
 * API como OAuth 2.0 Resource Server: JWT validado pelo decoder do Boot (JWKS, {@code iss}, {@code aud},
 * {@code exp}), autorização por escopo e {@code 401}/{@code 403} em ProblemDetail. Os probes de saúde ficam
 * públicos para o orquestrador.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	/** Claim do cliente que fez a chamada (RFC 9068): é o dono das solicitações. */
	static final String CLIENT_ID_CLAIM = "client_id";

	private static final int CLIENT_ID_MAX_LENGTH = 255;

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemAuthenticationEntryPoint entryPoint,
			ProblemAccessDeniedHandler accessDeniedHandler) {
		http.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health/**").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority("SCOPE_activation:write")
				.requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/*")
				.hasAuthority("SCOPE_activation:read")
				// rota não mapeada ou método não suportado: 401 sem token, antes do 404/405 do MVC
				.anyRequest().authenticated())
			.oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults())
				.authenticationEntryPoint(entryPoint)
				.accessDeniedHandler(accessDeniedHandler))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			// stateless, sem cookie de sessão: o token vai no header e o navegador não o envia sozinho
			.csrf(AbstractHttpConfigurer::disable);
		return http.build();
	}

	/** Somado pelo Boot aos validadores das propriedades: token sem {@code client_id} vira 401. */
	@Bean
	OAuth2TokenValidator<Jwt> clientIdValidator() {
		return new JwtClaimValidator<Object>(CLIENT_ID_CLAIM, value -> value instanceof String clientId
				&& !clientId.isBlank() && clientId.length() <= CLIENT_ID_MAX_LENGTH);
	}

}
