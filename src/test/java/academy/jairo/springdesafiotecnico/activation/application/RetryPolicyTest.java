package academy.jairo.springdesafiotecnico.activation.application;

import java.time.Duration;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

	private static final Duration BASE = Duration.ofSeconds(2);

	private static final Duration MAX = Duration.ofMinutes(5);

	private static final RetryPolicy LOWEST = new RetryPolicy(BASE, MAX, new FixedRandom(false));

	private static final RetryPolicy HIGHEST = new RetryPolicy(BASE, MAX, new FixedRandom(true));

	@ParameterizedTest(name = "tentativa {0}: entre {1} ms e {2} ms")
	@CsvSource({ "1, 1000, 2000", "2, 2000, 4000", "3, 4000, 8000" })
	void delayGrowsWithEachAttempt(int attempt, long lowestMillis, long highestMillis) {
		assertThat(LOWEST.delayFor(attempt)).isEqualTo(Duration.ofMillis(lowestMillis));
		assertThat(HIGHEST.delayFor(attempt)).isEqualTo(Duration.ofMillis(highestMillis));
	}

	@Test
	void delayIsCappedAtMax() {
		assertThat(LOWEST.delayFor(20)).isEqualTo(Duration.ofSeconds(150));
		assertThat(HIGHEST.delayFor(20)).isEqualTo(MAX);
	}

	@Test
	void highAttemptDoesNotOverflow() {
		assertThat(LOWEST.delayFor(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(150));
		assertThat(HIGHEST.delayFor(Integer.MAX_VALUE)).isEqualTo(MAX);
	}

	/** Sorteia sempre o menor ou sempre o maior valor do intervalo. */
	private record FixedRandom(boolean highest) implements RandomGenerator {

		@Override
		public long nextLong() {
			throw new UnsupportedOperationException();
		}

		@Override
		public long nextLong(long bound) {
			return highest ? bound - 1 : 0;
		}

	}

}
