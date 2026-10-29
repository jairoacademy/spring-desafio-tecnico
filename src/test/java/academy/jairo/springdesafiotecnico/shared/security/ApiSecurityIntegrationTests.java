package academy.jairo.springdesafiotecnico.shared.security;

import java.util.UUID;

import academy.jairo.springdesafiotecnico.TestTokens;
import academy.jairo.springdesafiotecnico.TestcontainersConfiguration;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTracing
class ApiSecurityIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ActivationRequestRepository repository;

	@Test
	void creationWithoutTokenIsUnauthorizedAndCreatesNothing() throws Exception {
		mockMvc.perform(create("EXT-SEC-NO-TOKEN")).andExpect(status().isUnauthorized());

		assertThat(repository.findByExternalId("EXT-SEC-NO-TOKEN")).isEmpty();
	}

	@Test
	void findByIdWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
	}

	@Test
	void listWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/requests")).andExpect(status().isUnauthorized());
	}

	@Test
	void malformedTokenIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/requests").header(HttpHeaders.AUTHORIZATION, "Bearer abc"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void creationWithReadScopeOnlyIsForbiddenAndCreatesNothing() throws Exception {
		mockMvc.perform(create("EXT-SEC-READ-ONLY").with(TestTokens.client("sistema-leitura", TestTokens.READ)))
			.andExpect(status().isForbidden());

		assertThat(repository.findByExternalId("EXT-SEC-READ-ONLY")).isEmpty();
	}

	@Test
	void findByIdWithWriteScopeOnlyIsForbidden() throws Exception {
		mockMvc
			.perform(get("/api/v1/requests/{id}", UUID.randomUUID())
				.with(TestTokens.client("sistema-escrita", TestTokens.WRITE)))
			.andExpect(status().isForbidden());
	}

	@Test
	void listWithoutScopeIsForbidden() throws Exception {
		mockMvc.perform(get("/api/v1/requests").with(TestTokens.client("sistema-sem-escopo")))
			.andExpect(status().isForbidden());
	}

	@Test
	void creationWithWriteScopeIsCreated() throws Exception {
		mockMvc.perform(create("EXT-SEC-WRITE").with(TestTokens.client("sistema-escrita", TestTokens.WRITE)))
			.andExpect(status().isCreated());
	}

	@Test
	void livenessIsPublic() throws Exception {
		mockMvc.perform(get("/actuator/health/liveness"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void readinessIsPublic() throws Exception {
		mockMvc.perform(get("/actuator/health/readiness"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	private static MockHttpServletRequestBuilder create(String externalId) {
		return post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content("""
				{"externalId":"%s","customerId":"CUST-1","productCode":"INTERNET-500"}""".formatted(externalId));
	}

}
