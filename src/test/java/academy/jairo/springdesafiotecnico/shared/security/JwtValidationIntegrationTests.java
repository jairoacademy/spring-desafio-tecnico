package academy.jairo.springdesafiotecnico.shared.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.function.Consumer;

import academy.jairo.springdesafiotecnico.TestcontainersConfiguration;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validação do token de ponta a ponta, pelo decoder do Boot: o WireMock serve o JWKS com a chave gerada no
 * teste, e os tokens são assinados com Nimbus. O {@code jwt()} do spring-security-test não serve aqui, porque
 * pula o decoder.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class JwtValidationIntegrationTests {

	// mesmos valores de src/test/resources/config/application.properties
	private static final String ISSUER = "https://issuer.test";

	private static final String AUDIENCE = "ativacao-api";

	private static final RSAKey TRUSTED_KEY = generateKey("chave-confiavel");

	private static final RSAKey UNKNOWN_KEY = generateKey("chave-desconhecida");

	@RegisterExtension
	static WireMockExtension authorizationServer = WireMockExtension.newInstance()
		.options(wireMockConfig().dynamicPort())
		.build();

	@DynamicPropertySource
	static void jwtProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
				() -> authorizationServer.baseUrl() + "/jwks");
	}

	@Autowired
	private MockMvc mockMvc;

	@BeforeEach
	void publishJwks() {
		authorizationServer
			.stubFor(WireMock.get("/jwks").willReturn(okJson(new JWKSet(TRUSTED_KEY.toPublicJWK()).toString())));
	}

	@Test
	void acceptsValidToken() throws Exception {
		list(sign(TRUSTED_KEY, claims -> {
		})).andExpect(status().isOk());
	}

	@Test
	void rejectsTokenSignedByKeyOutsideJwks() throws Exception {
		list(sign(UNKNOWN_KEY, claims -> {
		})).andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsTokenFromAnotherIssuer() throws Exception {
		list(sign(TRUSTED_KEY, claims -> claims.issuer("https://outro-emissor.test")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsTokenForAnotherAudience() throws Exception {
		list(sign(TRUSTED_KEY, claims -> claims.audience("outra-api"))).andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsTokenExpiredBeyondClockSkew() throws Exception {
		// tolerância padrão de 60 s: 5 min no passado está fora dela
		Instant expiredAt = Instant.now().minus(Duration.ofMinutes(5));
		list(sign(TRUSTED_KEY, claims -> claims.issueTime(Date.from(expiredAt.minus(Duration.ofMinutes(10))))
			.expirationTime(Date.from(expiredAt)))).andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsTokenWithoutClientId() throws Exception {
		list(sign(TRUSTED_KEY, claims -> claims.claim("client_id", null))).andExpect(status().isUnauthorized());
	}

	private ResultActions list(String token) throws Exception {
		return mockMvc.perform(get("/api/v1/requests").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	/** Token válido em tudo, com {@code activation:read}; {@code customizer} troca o que o cenário testa. */
	private static String sign(RSAKey key, Consumer<JWTClaimsSet.Builder> customizer) throws JOSEException {
		Instant now = Instant.now();
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(ISSUER)
			.audience(AUDIENCE)
			.subject("sistema-a")
			.claim("client_id", "sistema-a")
			.claim("scope", "activation:read")
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plus(Duration.ofMinutes(5))));
		customizer.accept(claims);
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
				claims.build());
		jwt.sign(new RSASSASigner(key));
		return jwt.serialize();
	}

	private static RSAKey generateKey(String keyId) {
		try {
			return new RSAKeyGenerator(2048).keyID(keyId).generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
