package academy.jairo.springdesafiotecnico.activation.domain;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus.COMPLETED;
import static academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus.FAILED;
import static academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus.PENDING;
import static academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus.PROCESSING;
import static org.assertj.core.api.Assertions.assertThat;

class ActivationStatusTest {

	private static final Map<ActivationStatus, Set<ActivationStatus>> ALLOWED = Map.of(
			PENDING, Set.of(PROCESSING),
			PROCESSING, Set.of(COMPLETED, FAILED, PENDING),
			COMPLETED, Set.of(),
			FAILED, Set.of());

	static Stream<Arguments> allowedTransitions() {
		return allPairs().filter(pair -> isAllowed(pair));
	}

	static Stream<Arguments> forbiddenTransitions() {
		return allPairs().filter(pair -> !isAllowed(pair));
	}

	private static Stream<Arguments> allPairs() {
		return Stream.of(ActivationStatus.values())
				.flatMap(from -> Stream.of(ActivationStatus.values()).map(to -> Arguments.of(from, to)));
	}

	private static boolean isAllowed(Arguments pair) {
		Object[] values = pair.get();
		return ALLOWED.get((ActivationStatus) values[0]).contains((ActivationStatus) values[1]);
	}

	@ParameterizedTest(name = "{0} -> {1} é permitida")
	@MethodSource("allowedTransitions")
	void allowsTransition(ActivationStatus from, ActivationStatus to) {
		assertThat(from.canTransitionTo(to)).isTrue();
	}

	@ParameterizedTest(name = "{0} -> {1} é proibida")
	@MethodSource("forbiddenTransitions")
	void forbidsTransition(ActivationStatus from, ActivationStatus to) {
		assertThat(from.canTransitionTo(to)).isFalse();
	}

}
