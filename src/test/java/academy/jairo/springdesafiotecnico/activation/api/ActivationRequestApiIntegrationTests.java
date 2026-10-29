package academy.jairo.springdesafiotecnico.activation.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import academy.jairo.springdesafiotecnico.TestTokens;
import academy.jairo.springdesafiotecnico.TestcontainersConfiguration;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.domain.FailureReason;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
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

	private static final String DUPLICATE_TYPE = "https://problems.ativacao.example/duplicate-external-id";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ActivationRequestRepository repository;

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
			.andExpect(jsonPath("$.failureReason").value(nullValue()))
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

		MvcResult fetched = perform(get(location)).andExpect(status().isOk()).andReturn();

		Map<String, Object> createdBody = JsonPath.read(created.getResponse().getContentAsString(), "$");
		Map<String, Object> fetchedBody = JsonPath.read(fetched.getResponse().getContentAsString(), "$");
		assertThat(fetchedBody).isEqualTo(createdBody);
	}

	@Test
	void returnsExistingRequestWithCurrentStatus() throws Exception {
		MvcResult created = postRequest(validBody("EXT-GET")).andExpect(status().isCreated()).andReturn();
		String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

		perform(get("/api/v1/requests/{id}", id))
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
		expectProblem(perform(get("/api/v1/requests/{id}", unknown)), 404)
			.andExpect(jsonPath("$.type").value("https://problems.ativacao.example/not-found"))
			.andExpect(jsonPath("$.title").value("Solicitação não encontrada"))
			.andExpect(jsonPath("$.instance").value("/api/v1/requests/" + unknown));
	}

	@Test
	void rejectsIdThatIsNotUuid() throws Exception {
		expectProblem(perform(get("/api/v1/requests/abc")), 400)
			.andExpect(jsonPath("$.type").value("https://problems.ativacao.example/invalid-parameter"))
			.andExpect(jsonPath("$.instance").value("/api/v1/requests/abc"));
	}

	@Test
	void rejectsUnsupportedMethod() throws Exception {
		expectProblem(perform(delete("/api/v1/requests/{id}", UUID.randomUUID())), 405);
	}

	@Test
	void traceIdIsDistinctPerRequest() throws Exception {
		String first = traceIdOf(expectProblem(perform(get("/api/v1/requests/{id}", UUID.randomUUID())), 404));
		String second = traceIdOf(expectProblem(perform(get("/api/v1/requests/{id}", UUID.randomUUID())), 404));

		assertThat(first).isNotEqualTo(second);
	}

	@Test
	void listsOnlyRequestsOfGivenCustomer() throws Exception {
		String customerA = uniqueCustomer("CUST-A");
		String customerB = uniqueCustomer("CUST-B");
		postRequest(body("EXT-A1", customerA)).andExpect(status().isCreated());
		postRequest(body("EXT-A2", customerA)).andExpect(status().isCreated());
		postRequest(body("EXT-B1", customerB)).andExpect(status().isCreated());

		perform(get("/api/v1/requests").param("customerId", customerA))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(2))
			.andExpect(jsonPath("$.content[*].customerId").value(everyItem(is(customerA))))
			.andExpect(jsonPath("$.totalElements").value(2));
	}

	@Test
	void combinesStatusAndCustomerFilters() throws Exception {
		String customer = uniqueCustomer("CUST-A");
		postRequest(body("EXT-PENDING", customer)).andExpect(status().isCreated());
		// nenhum endpoint desta change muda status: a solicitação PROCESSING é gravada direto pelo repositório
		ActivationRequest processing = ActivationRequest.create(TestTokens.DEFAULT_CLIENT, "EXT-PROCESSING", customer,
				"INTERNET-500", Instant.now());
		processing.startProcessing(Instant.now());
		repository.save(processing);
		postRequest(body("EXT-OTHER", uniqueCustomer("CUST-B"))).andExpect(status().isCreated());

		perform(get("/api/v1/requests").param("status", "PENDING").param("customerId", customer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].externalId").value("EXT-PENDING"))
			.andExpect(jsonPath("$.content[0].status").value("PENDING"))
			.andExpect(jsonPath("$.content[0].customerId").value(customer));
	}

	@Test
	void exposesFailureReasonOfFailedRequest() throws Exception {
		String customer = uniqueCustomer("CUST-F");
		// a falha é gravada direto pelo repositório, sem passar pelo provedor
		ActivationRequest failed = ActivationRequest.create(TestTokens.DEFAULT_CLIENT, "EXT-FAILED-" + customer,
				customer, "INTERNET-500", Instant.now());
		failed.startProcessing(Instant.now());
		failed.fail(FailureReason.MAX_ATTEMPTS_EXCEEDED, Instant.now());
		repository.save(failed);

		perform(get("/api/v1/requests/{id}", failed.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.failureReason").value("MAX_ATTEMPTS_EXCEEDED"));
		perform(get("/api/v1/requests").param("status", "FAILED").param("customerId", customer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].id").value(failed.getId().toString()))
			.andExpect(jsonPath("$.content[0].failureReason").value("MAX_ATTEMPTS_EXCEEDED"));
	}

	@Test
	void paginatesNewestFirst() throws Exception {
		String customer = uniqueCustomer("CUST-P");
		postRequest(body("EXT-P1", customer)).andExpect(status().isCreated());
		postRequest(body("EXT-P2", customer)).andExpect(status().isCreated());
		postRequest(body("EXT-P3", customer)).andExpect(status().isCreated());

		perform(get("/api/v1/requests").param("customerId", customer).param("page", "0").param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(2))
			.andExpect(jsonPath("$.content[0].externalId").value("EXT-P3"))
			.andExpect(jsonPath("$.content[1].externalId").value("EXT-P2"))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(2))
			.andExpect(jsonPath("$.totalElements").value(3))
			.andExpect(jsonPath("$.totalPages").value(2));
	}

	@Test
	void usesDefaultPagination() throws Exception {
		perform(get("/api/v1/requests").param("customerId", uniqueCustomer("CUST-EMPTY")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void rejectsUnknownStatusFilter() throws Exception {
		expectProblem(perform(get("/api/v1/requests").param("status", "DONE")), 400)
			.andExpect(jsonPath("$.type").value("https://problems.ativacao.example/invalid-parameter"));
	}

	@Test
	void rejectsPageSizeAboveLimit() throws Exception {
		expectProblem(perform(get("/api/v1/requests").param("size", "101")), 400)
			.andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
			.andExpect(jsonPath("$.errors[*].field").value(hasItem("size")));
	}

	@Test
	void rejectsNegativePage() throws Exception {
		expectProblem(perform(get("/api/v1/requests").param("page", "-1")), 400)
			.andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
			.andExpect(jsonPath("$.errors[*].field").value(hasItem("page")));
	}

	@Test
	void invalidCreationIsNotPersisted() throws Exception {
		String customer = uniqueCustomer("CUST-INVALID");
		postRequest("""
				{"externalId":"EXT-1","customerId":"%s","productCode":"internet 500"}""".formatted(customer))
			.andExpect(status().isBadRequest());

		perform(get("/api/v1/requests").param("customerId", customer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(0))
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void identicalRetryReturnsExistingRequest() throws Exception {
		String body = """
				{"externalId":"EXT-RETRY","customerId":"CUST-1","productCode":"INTERNET-500"}""";
		MvcResult created = postRequest(body).andExpect(status().isCreated()).andReturn();
		Map<String, Object> createdBody = JsonPath.read(created.getResponse().getContentAsString(), "$");

		MvcResult retried = postRequest(body).andExpect(status().isOk())
			.andExpect(header().doesNotExist("Location"))
			.andReturn();

		Map<String, Object> retriedBody = JsonPath.read(retried.getResponse().getContentAsString(), "$");
		assertThat(retriedBody).isEqualTo(createdBody);
		assertThat(requestsWithExternalId("EXT-RETRY")).hasSize(1);
	}

	@Test
	void retryReturnsCurrentStatus() throws Exception {
		String body = """
				{"externalId":"EXT-RETRY-2","customerId":"CUST-1","productCode":"INTERNET-500"}""";
		postRequest(body).andExpect(status().isCreated());
		// nenhum endpoint desta change muda status: a transição é gravada direto pelo repositório
		ActivationRequest stored = repository.findByExternalId("EXT-RETRY-2").orElseThrow();
		stored.startProcessing(Instant.now());
		repository.save(stored);

		postRequest(body).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(stored.getId().toString()))
			.andExpect(jsonPath("$.status").value("PROCESSING"));
	}

	@Test
	void divergentProductCodeIsConflict() throws Exception {
		postRequest("""
				{"externalId":"EXT-CONF","customerId":"CUST-1","productCode":"INTERNET-500"}""")
			.andExpect(status().isCreated());

		expectProblem(postRequest("""
				{"externalId":"EXT-CONF","customerId":"CUST-1","productCode":"INTERNET-900"}"""), 409)
			.andExpect(jsonPath("$.type").value(DUPLICATE_TYPE))
			.andExpect(jsonPath("$.title").value("Solicitação duplicada"))
			.andExpect(jsonPath("$.detail").value(containsString("EXT-CONF")))
			.andExpect(jsonPath("$.conflictingFields").value(contains("productCode")))
			.andExpect(jsonPath("$.id").doesNotExist())
			.andExpect(jsonPath("$.customerId").doesNotExist())
			.andExpect(jsonPath("$.productCode").doesNotExist());
	}

	@Test
	void reportsEveryDivergentField() throws Exception {
		postRequest("""
				{"externalId":"EXT-CONF-2","customerId":"CUST-1","productCode":"INTERNET-500"}""")
			.andExpect(status().isCreated());

		expectProblem(postRequest("""
				{"externalId":"EXT-CONF-2","customerId":"CUST-2","productCode":"INTERNET-900"}"""), 409)
			.andExpect(jsonPath("$.type").value(DUPLICATE_TYPE))
			.andExpect(jsonPath("$.conflictingFields").value(contains("customerId", "productCode")));
	}

	@Test
	void sameExternalIdFromAnotherCustomerIsConflict() throws Exception {
		postRequest("""
				{"externalId":"EXT-DUP-2","customerId":"CUST-A","productCode":"INTERNET-500"}""")
			.andExpect(status().isCreated());

		expectProblem(postRequest("""
				{"externalId":"EXT-DUP-2","customerId":"CUST-B","productCode":"INTERNET-500"}"""), 409)
			.andExpect(jsonPath("$.conflictingFields").value(contains("customerId")));

		assertThat(requestsWithExternalId("EXT-DUP-2")).singleElement()
			.extracting(ActivationRequest::getCustomerId)
			.isEqualTo("CUST-A");
	}

	@Test
	void conflictDoesNotChangeExistingRequest() throws Exception {
		MvcResult created = postRequest("""
				{"externalId":"EXT-CONF-3","customerId":"CUST-1","productCode":"INTERNET-500"}""")
			.andExpect(status().isCreated())
			.andReturn();
		Map<String, Object> createdBody = JsonPath.read(created.getResponse().getContentAsString(), "$");
		postRequest("""
				{"externalId":"EXT-CONF-3","customerId":"CUST-2","productCode":"INTERNET-900"}""")
			.andExpect(status().isConflict());

		MvcResult fetched = perform(get(created.getResponse().getHeader("Location")))
			.andExpect(status().isOk())
			.andReturn();

		Map<String, Object> fetchedBody = JsonPath.read(fetched.getResponse().getContentAsString(), "$");
		assertThat(fetchedBody).isEqualTo(createdBody);
	}

	@Test
	void externalIdDifferingOnlyInCaseCreatesAnotherRequest() throws Exception {
		postRequest(validBody("EXT-CASE")).andExpect(status().isCreated());

		postRequest(validBody("ext-case")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.externalId").value("ext-case"));

		assertThat(requestsWithExternalId("EXT-CASE")).hasSize(1);
		assertThat(requestsWithExternalId("ext-case")).hasSize(1);
	}

	private List<ActivationRequest> requestsWithExternalId(String externalId) {
		return repository.findAll().stream().filter(request -> request.getExternalId().equals(externalId)).toList();
	}

	private ResultActions postRequest(String body) throws Exception {
		return perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request.with(TestTokens.readWrite()));
	}

	private static String uniqueCustomer(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private static String body(String externalId, String customerId) {
		return """
				{"externalId":"%s","customerId":"%s","productCode":"INTERNET-500"}""".formatted(externalId, customerId);
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
