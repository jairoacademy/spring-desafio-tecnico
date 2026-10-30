package academy.jairo.springdesafiotecnico.archfixture.activation.domain;

import academy.jairo.springdesafiotecnico.archfixture.activation.infrastructure.FixtureAdapter;

/**
 * Fixture do {@code LayerDependencyRulesTest}: violação proposital (domain depende de infrastructure).
 */
public class FixtureViolation {

	private final FixtureAdapter adapter = new FixtureAdapter();

	public FixtureAdapter adapter() {
		return adapter;
	}
}
