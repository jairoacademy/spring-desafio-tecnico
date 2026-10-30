package academy.jairo.springdesafiotecnico;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@AnalyzeClasses(packages = "academy.jairo.springdesafiotecnico", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependencyRulesTest {

	@ArchTest
	static final ArchRule layersAreRespected = layers();

	static ArchRule layers() {
		return layeredArchitecture().consideringOnlyDependenciesInLayers()
				.layer("Api").definedBy("..activation.api..")
				.layer("Application").definedBy("..activation.application..")
				.layer("Infrastructure").definedBy("..activation.infrastructure..")
				.layer("Domain").definedBy("..activation.domain..")
				.whereLayer("Api").mayNotBeAccessedByAnyLayer()
				.whereLayer("Application").mayOnlyBeAccessedByLayers("Api")
				.whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Application")
				.whereLayer("Domain").mayOnlyBeAccessedByLayers("Api", "Application", "Infrastructure");
	}

	@Test
	void domainImportingInfrastructureBreaksTheRule() {
		JavaClasses fixtures = new ClassFileImporter()
				.importPackages("academy.jairo.springdesafiotecnico.archfixture");
		assertThat(fixtures).isNotEmpty();

		assertThatThrownBy(() -> layers().check(fixtures))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("FixtureViolation")
				.hasMessageContaining("FixtureAdapter");
	}
}
