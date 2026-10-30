package academy.jairo.springdesafiotecnico;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Contexto próprio, com o agendamento ligado: fecha a aplicação com um envio em andamento e confere que o
 * resultado foi gravado antes de o acesso ao banco fechar.
 */
@Testcontainers
class GracefulShutdownIntegrationTests {

	private static final String ACTIVATE = "/provider/v1/activate";

	@Container
	static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

	@RegisterExtension
	static WireMockExtension provider = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	@Test
	void inFlightDispatchCompletesBeforeShutdown() throws Exception {
		provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200).withFixedDelay(2000)));
		UUID id = UUID.randomUUID();

		ConfigurableApplicationContext context = new SpringApplicationBuilder(SpringdesafiotecnicoApplication.class)
			.bannerMode(Banner.Mode.OFF)
			.run("--server.port=0", "--activation.dispatch.enabled=true", "--activation.dispatch.interval=100ms",
					"--spring.datasource.url=" + postgres.getJdbcUrl(),
					"--spring.datasource.username=" + postgres.getUsername(),
					"--spring.datasource.password=" + postgres.getPassword(),
					"--spring.http.serviceclient.provider.base-url=" + provider.baseUrl(),
					"--spring.http.serviceclient.provider.read-timeout=10s",
					"--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9/jwks",
					"--spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test",
					"--spring.security.oauth2.resourceserver.jwt.audiences=ativacao-api");
		try {
			insertPending(id);
			await().atMost(Duration.ofSeconds(10))
				.untilAsserted(() -> provider.verify(1, postRequestedFor(urlEqualTo(ACTIVATE))));
		}
		finally {
			context.close();
		}

		assertThat(statusOf(id)).isEqualTo("COMPLETED");
	}

	private static void insertPending(UUID id) throws SQLException {
		try (Connection connection = connection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO activation_request (id, client_id, external_id, customer_id, product_code, status,
						    attempts, next_attempt_at, created_at, updated_at, version)
						VALUES (?, 'sistema-teste', ?, 'CUST-1', 'INTERNET-500', 'PENDING', 0, now(), now(), now(), 0)
						""")) {
			statement.setObject(1, id);
			statement.setString(2, "EXT-SHUTDOWN-" + id);
			statement.executeUpdate();
		}
	}

	private static String statusOf(UUID id) throws SQLException {
		try (Connection connection = connection();
				PreparedStatement statement = connection
					.prepareStatement("SELECT status FROM activation_request WHERE id = ?")) {
			statement.setObject(1, id);
			try (ResultSet result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				return result.getString(1);
			}
		}
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}
