package academy.jairo.springdesafiotecnico;

import java.util.Arrays;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * JWT de teste para o MockMvc: autentica direto, sem passar pelo decoder (assinatura, {@code iss}, {@code aud}
 * e {@code exp} são cobertos por {@code JwtValidationIntegrationTests}).
 */
public final class TestTokens {

	public static final String READ = "activation:read";

	public static final String WRITE = "activation:write";

	/** Cliente padrão dos testes que não tratam de dono. */
	public static final String DEFAULT_CLIENT = "sistema-teste";

	private TestTokens() {
	}

	/** Token do cliente padrão com leitura e escrita. */
	public static JwtRequestPostProcessor readWrite() {
		return client(DEFAULT_CLIENT, READ, WRITE);
	}

	/** Token com a claim {@code client_id} e só os escopos informados. */
	public static JwtRequestPostProcessor client(String clientId, String... scopes) {
		return jwt().jwt(token -> token.claim("client_id", clientId))
			.authorities(Arrays.stream(scopes).<GrantedAuthority>map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope)).toList());
	}

}
