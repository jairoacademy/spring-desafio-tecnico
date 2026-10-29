package academy.jairo.springdesafiotecnico.activation.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTest {

	private static final Instant T0 = Instant.parse("2026-01-10T12:00:00.123456789Z");

	private static final Instant T1 = T0.plusSeconds(10);

	private static final Instant T2 = T0.plusSeconds(20);

	private static ActivationRequest newRequest() {
		return ActivationRequest.create("EXT-1", "CUST-1", "INTERNET-500", T0);
	}

	private static ActivationRequest processingRequest() {
		ActivationRequest request = newRequest();
		request.startProcessing(T1);
		return request;
	}

	@Test
	void startsPendingWithNoAttemptsAndEligibleImmediately() {
		ActivationRequest request = newRequest();

		Instant truncated = T0.truncatedTo(ChronoUnit.MICROS);
		assertThat(request.getId()).isNotNull();
		assertThat(request.getExternalId()).isEqualTo("EXT-1");
		assertThat(request.getCustomerId()).isEqualTo("CUST-1");
		assertThat(request.getProductCode()).isEqualTo("INTERNET-500");
		assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertThat(request.getAttempts()).isZero();
		assertThat(request.getCreatedAt()).isEqualTo(truncated);
		assertThat(request.getUpdatedAt()).isEqualTo(truncated);
		assertThat(request.getNextAttemptAt()).isEqualTo(request.getCreatedAt());
		assertThat(request.getVersion()).isNull();
	}

	@Test
	void successPathEndsCompleted() {
		ActivationRequest request = processingRequest();

		request.complete(T2);

		assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
		assertThat(request.getUpdatedAt()).isEqualTo(T2.truncatedTo(ChronoUnit.MICROS));
	}

	@Test
	void definitiveFailureEndsFailed() {
		ActivationRequest request = processingRequest();

		request.fail(T2);

		assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
		assertThat(request.getUpdatedAt()).isEqualTo(T2.truncatedTo(ChronoUnit.MICROS));
	}

	@Test
	void temporaryFailureSchedulesRetry() {
		ActivationRequest request = processingRequest();
		Instant nextAttempt = T2.plusSeconds(60);

		request.scheduleRetry(nextAttempt, T2);

		assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertThat(request.getAttempts()).isEqualTo(1);
		assertThat(request.getNextAttemptAt()).isEqualTo(nextAttempt.truncatedTo(ChronoUnit.MICROS));
		assertThat(request.getUpdatedAt()).isEqualTo(T2.truncatedTo(ChronoUnit.MICROS));
	}

	@Test
	void sameContentHasNoConflictingFields() {
		assertThat(newRequest().conflictingFields("CUST-1", "INTERNET-500")).isEmpty();
	}

	@Test
	void reportsDivergentProductCode() {
		assertThat(newRequest().conflictingFields("CUST-1", "INTERNET-900")).containsExactly("productCode");
	}

	@Test
	void reportsDivergentCustomerId() {
		assertThat(newRequest().conflictingFields("CUST-2", "INTERNET-500")).containsExactly("customerId");
	}

	@Test
	void reportsBothDivergentFieldsInJsonOrder() {
		assertThat(newRequest().conflictingFields("CUST-2", "INTERNET-900")).containsExactly("customerId",
				"productCode");
	}

	static Stream<Arguments> rejectedTransitions() {
		Consumer<ActivationRequest> toCompleted = r -> {
			r.startProcessing(T1);
			r.complete(T1);
		};
		Consumer<ActivationRequest> toFailed = r -> {
			r.startProcessing(T1);
			r.fail(T1);
		};
		Consumer<ActivationRequest> stayPending = r -> {
		};
		Consumer<ActivationRequest> toProcessing = r -> r.startProcessing(T1);
		return Stream.of(
				Arguments.of("PENDING -> COMPLETED", stayPending, (Consumer<ActivationRequest>) r -> r.complete(T2)),
				Arguments.of("PENDING -> FAILED", stayPending, (Consumer<ActivationRequest>) r -> r.fail(T2)),
				Arguments.of("PENDING -> PENDING (retry)", stayPending,
						(Consumer<ActivationRequest>) r -> r.scheduleRetry(T2, T2)),
				Arguments.of("PROCESSING -> PROCESSING", toProcessing,
						(Consumer<ActivationRequest>) r -> r.startProcessing(T2)),
				Arguments.of("COMPLETED -> PROCESSING", toCompleted,
						(Consumer<ActivationRequest>) r -> r.startProcessing(T2)),
				Arguments.of("COMPLETED -> FAILED", toCompleted, (Consumer<ActivationRequest>) r -> r.fail(T2)),
				Arguments.of("COMPLETED -> PENDING (retry)", toCompleted,
						(Consumer<ActivationRequest>) r -> r.scheduleRetry(T2, T2)),
				Arguments.of("FAILED -> PROCESSING", toFailed, (Consumer<ActivationRequest>) r -> r.startProcessing(T2)),
				Arguments.of("FAILED -> COMPLETED", toFailed, (Consumer<ActivationRequest>) r -> r.complete(T2)),
				Arguments.of("FAILED -> PENDING (retry)", toFailed,
						(Consumer<ActivationRequest>) r -> r.scheduleRetry(T2, T2)));
	}

	@ParameterizedTest(name = "{0} é rejeitada")
	@MethodSource("rejectedTransitions")
	void rejectsInvalidTransitionWithoutChangingRequest(String name, Consumer<ActivationRequest> arrange,
			Consumer<ActivationRequest> act) {
		ActivationRequest request = newRequest();
		arrange.accept(request);
		ActivationStatus statusBefore = request.getStatus();
		Instant updatedAtBefore = request.getUpdatedAt();
		int attemptsBefore = request.getAttempts();
		Instant nextAttemptAtBefore = request.getNextAttemptAt();

		assertThatThrownBy(() -> act.accept(request)).isInstanceOf(InvalidStatusTransitionException.class);

		assertThat(request.getStatus()).isEqualTo(statusBefore);
		assertThat(request.getUpdatedAt()).isEqualTo(updatedAtBefore);
		assertThat(request.getAttempts()).isEqualTo(attemptsBefore);
		assertThat(request.getNextAttemptAt()).isEqualTo(nextAttemptAtBefore);
	}

}
