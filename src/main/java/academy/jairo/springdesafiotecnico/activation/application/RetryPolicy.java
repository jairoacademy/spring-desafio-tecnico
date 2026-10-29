package academy.jairo.springdesafiotecnico.activation.application;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Backoff exponencial com "equal jitter": para a tentativa n, {@code D = min(max, base × 2^(n-1))}
 * e o atraso é sorteado em {@code [D/2, D]}. A metade fixa garante atraso maior que zero; a sorteada
 * espalha as novas tentativas de solicitações que falharam juntas.
 */
public class RetryPolicy {

	private final long baseMillis;

	private final long maxMillis;

	private final RandomGenerator random;

	public RetryPolicy(Duration base, Duration max, RandomGenerator random) {
		if (base.isNegative() || base.isZero() || max.compareTo(base) < 0) {
			throw new IllegalArgumentException("Backoff exige 0 < base <= max: base=" + base + ", max=" + max);
		}
		this.baseMillis = base.toMillis();
		this.maxMillis = max.toMillis();
		this.random = random;
	}

	/** Atraso da tentativa {@code attempt} (contador já incrementado, a partir de 1). */
	public Duration delayFor(int attempt) {
		if (attempt < 1) {
			throw new IllegalArgumentException("Tentativa começa em 1: " + attempt);
		}
		long ceiling = ceiling(attempt);
		long half = ceiling / 2;
		return Duration.ofMillis(half + random.nextLong(ceiling - half + 1));
	}

	private long ceiling(int attempt) {
		int exponent = attempt - 1;
		// base × 2^exponent passaria de max (ou estouraria o long): fica no teto
		if (exponent >= Long.numberOfLeadingZeros(baseMillis) - 1) {
			return maxMillis;
		}
		return Math.min(maxMillis, baseMillis << exponent);
	}

}
