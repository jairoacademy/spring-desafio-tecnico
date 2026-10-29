package academy.jairo.springdesafiotecnico.activation.api;

import java.util.Map;
import java.util.UUID;

import academy.jairo.springdesafiotecnico.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTracing
class ActivationRequestApiIntegrationTests {

	private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

	private static final String ISO_UTC_PATTERN = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$";

	private static final String TRACE_ID_PATTERN = "^[0-9a-f]{32}$";

	private static final String VALIDATION_TYPE = "https://problems.ativacao.example/validation-error";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void createsRequestWithValidData() throws Exception {
		postRequest("""
				{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}""")
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", matchesPattern(".*/api/v1/requests/[0-9a-f-]{36}$")))
			.andExpect(jsonPath("$.id").value(matchesPattern(UUID_PATTERN)))
			.andExpect(jsonPath("$.externalId").value("EXT-12345"))
			.andExpect(jsonPath("$.customerId").value("CUST-10001"))
			.andExpect(jsonPath("$.productCode").value("INTERNET-500"))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.attempts").doesNotExist())
			.andExpect(jsonPath("$.nextAttemptAt").doesNotExist())
			.andExpect(jsonPath("$.version").doesNotExist());
	}

	@Test
	void locationHeaderPointsToCreatedId() throws Exception {
		MvcResult result = postRequest(validBody("EXT-LOC")).andExpect(status().isCreated()).andReturn();

		String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		assertThat(result.getResponse().getHeader("Location")).endsWith("/api/v1/requests/" + id);
	}

	@Test
	void createdAtAndUpdatedAtAreEqualIsoUtc() throws Exception {
		MvcResult result = postRequest(validBody("EXT-DATES"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.createdAt").value(matchesPattern(ISO_UTC_PATTERN)))
			.andExpect(jsonPath("$.updatedAt").value(matchesPattern(ISO_UTC_PATTERN)))
			.andReturn();

		String json = result.getResponse().getContentAsString();
		String createdAt = JsonPath.read(json, "$.createdAt");
		String updatedAt = JsonPath.read(json, "$.updatedAt");
		assertThat(createdAt).isEqualTo(updatedAt);
	}

	@Test
	void getOnLocationReturnsSameDataAsCreation() throws Exception {
		MvcResult created = postRequest(validBody("EXT-PERSIST")).andExpect(status().isCreated()).andReturn();
		String location = created.getResponse().getHeader("Location");

		MvcResult fetched = mockMvc.perform(get(location)).andExpect(status().isOk()).andReturn();

		Map<String, Object> createdBody = JsonPath.read(created.getResponse().getContentAsString(), "$");
		Map<String, Object> fetchedBody = JsonPath.read(fetched.getResponse().getContentAsString(), "$");
		assertThat(fetchedBody).isEqualTo(createdBody);
	}

	@Test
	void returnsExistingRequestWithCurrentStatus() throws Exception {
		MvcResult created = postRequest(validBody("EXT-GET")).andExpect(status().isCreated()).andReturn();
		String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

		mockMvc.perform(get("/api/v1/requests/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.externalId").value("EXT-GET"))
			.andExpect(jsonPath("$.status").value("PENDING"));
	}

	@Test
	void rejectsMissingField() throws Exception {
		expectProblem(postRequest("""
				{"externalId":"EXT-1","productCode":"INTERNET-500"}"""), 400)
			.andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
			.andExpect(jsonPath("$.title").value("Dados inválidos"))
			.andExpect(jsonPath("$.errors[*].field").value(hasItem("customerId")))
			.andExpect(jsonPath("$.errors[0].message").value(not(blankOrNullString())));
	}

	@Test
	void rejectsFieldAboveMaxLength() throws Exception {
		String tooLong = "A".repeat(65);
		expectProblem(postRequest("""
				{"externalId":"%s","customerId":"CUST-1","productCode":"INTERNET-500"}""".formatted(tooLong)), 400)
			.andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
			.andExpect(jsonPath("$.errors[*].field").value(hasItem("externalId")));
	}

	@Test
	void rejectsFieldWithInvalidFormat() throws Exception {
		expectProblem(postRequest("""
				{"externalId":"EXT-1","customerId":"CUST-1","productCode":"internet 500"}"""), 400)
			.andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
			.andExpect(jsonPath("$.errors[*].field").value(hasItem("productCode")));
	}

	@Test
	void reportsEveryInvalidFieldAtOnce() throws Exception {
		expectProblem(postRequest("{}"), 400)
			.andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
			.andExpect(jsonPath("$.errors[*].field").value(hasItems("externalId", "customerId", "productCode")));
	}

	@Test
	void rejectsMalformedJsonWithoutParserDetails() throws Exception {
		MvcResult result = expectProblem(postRequest("{\"externalId\":"), 400)
			.andExpect(jsonPath("$.type").value("https://problems.ativacao.example/malformed-body"))
			.andReturn();

		String detail = JsonPath.read(result.getResponse().getContentAsString(), "$.detail");
		assertThat(detail).doesNotContain("jackson", "Jackson", "Exception", "Unexpected", "line:", "column:",
				"CreateActivationRequest");
	}

	@Test
	void returnsNotFoundForUnknownId() throws Exception {
		UUID unknown = UUID.randomUUID();
		expectProblem(mockMvc.perform(get("/api/v1/requests/{id}", unknown)), 404)
			.andExpect(jsonPath("$.type").value("https://problems.ativacao.example/not-found"))
			.andExpect(jsonPath("$.title").value("Solicitação não encontrada"))
			.andExpect(jsonPath("$.instance").value("/api/v1/requests/" + unknown));
	}

	@Test
	void rejectsIdThatIsNotUuid() throws Exception {
		expectProblem(mockMvc.perform(get("/api/v1/requests/abc")), 400)
			.andExpect(jsonPath("$.type").value("https://problems.ativacao.example/invalid-parameter"))
			.andExpect(jsonPath("$.instance").value("/api/v1/requests/abc"));
	}

	@Test
	void rejectsUnsupportedMethod() throws Exception {
		expectProblem(mockMvc.perform(delete("/api/v1/requests/{id}", UUID.randomUUID())), 405);
	}

	@Test
	void traceIdIsDistinctPerRequest() throws Exception {
		String first = traceIdOf(expectProblem(mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID())), 404));
		String second = traceIdOf(expectProblem(mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID())), 404));

		assertThat(first).isNotEqualTo(second);
	}

	private ResultActions postRequest(String body) throws Exception {
		return mockMvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String validBody(String externalId) {
		return """
				{"externalId":"%s","customerId":"CUST-10001","productCode":"INTERNET-500"}""".formatted(externalId);
	}

	/** Verifica o formato comum a todo erro: ProblemDetail completo com traceId. */
	private static ResultActions expectProblem(ResultActions actions, int status) throws Exception {
		return actions.andExpect(status().is(status))
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.type").value(not(blankOrNullString())))
			.andExpect(jsonPath("$.title").value(not(blankOrNullString())))
			.andExpect(jsonPath("$.detail").value(not(blankOrNullString())))
			.andExpect(jsonPath("$.instance").value(not(blankOrNullString())))
			.andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
	}

	private static String traceIdOf(ResultActions actions) throws Exception {
		return JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.traceId");
	}

}
