package academy.jairo.springdesafiotecnico.activation.infrastructure;

import java.time.Instant;

import academy.jairo.springdesafiotecnico.TestcontainersConfiguration;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Sem transação do teste: cada chamada ao repositório confirma, e as cópias lidas ficam destacadas.
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivationRequestRepositoryTests {

	private static final Instant NOW = Instant.parse("2026-01-10T12:00:00.123456789Z");

	@Autowired
	private ActivationRequestRepository repository;

	@AfterEach
	void cleanUp() {
		repository.deleteAll();
	}

	@Test
	void savesAndReadsBackAllFields() {
		ActivationRequest saved = repository.save(ActivationRequest.create("EXT-1", "CUST-1", "INTERNET-500", NOW));

		ActivationRequest found = repository.findById(saved.getId()).orElseThrow();

		assertThat(found.getExternalId()).isEqualTo("EXT-1");
		assertThat(found.getCustomerId()).isEqualTo("CUST-1");
		assertThat(found.getProductCode()).isEqualTo("INTERNET-500");
		assertThat(found.getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertThat(found.getAttempts()).isZero();
		assertThat(found.getCreatedAt()).isEqualTo(saved.getCreatedAt());
		assertThat(found.getUpdatedAt()).isEqualTo(saved.getUpdatedAt());
		assertThat(found.getNextAttemptAt()).isEqualTo(saved.getCreatedAt());
		assertThat(found.getVersion()).isZero();
	}

	@Test
	void rejectsSaveFromStaleCopy() {
		ActivationRequest saved = repository.save(ActivationRequest.create("EXT-2", "CUST-1", "INTERNET-500", NOW));
		ActivationRequest first = repository.findById(saved.getId()).orElseThrow();
		ActivationRequest second = repository.findById(saved.getId()).orElseThrow();

		first.startProcessing(NOW.plusSeconds(1));
		repository.save(first);
		second.startProcessing(NOW.plusSeconds(2));

		assertThatThrownBy(() -> repository.save(second)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
		ActivationRequest current = repository.findById(saved.getId()).orElseThrow();
		assertThat(current.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
		assertThat(current.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
		assertThat(current.getVersion()).isEqualTo(1L);
	}

	@Test
	void rejectsSecondRequestWithSameExternalId() {
		ActivationRequest first = repository
			.saveAndFlush(ActivationRequest.create("EXT-DUP", "CUST-1", "INTERNET-500", NOW));
		ActivationRequest duplicate = ActivationRequest.create("EXT-DUP", "CUST-2", "INTERNET-900", NOW);

		assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(repository.findAll()).extracting(ActivationRequest::getId).containsExactly(first.getId());
	}

	@Test
	void findsByExternalId() {
		ActivationRequest saved = repository.save(ActivationRequest.create("EXT-FIND", "CUST-1", "INTERNET-500", NOW));

		assertThat(repository.findByExternalId("EXT-FIND")).map(ActivationRequest::getId).contains(saved.getId());
	}

	@Test
	void findByUnknownExternalIdIsEmpty() {
		assertThat(repository.findByExternalId("EXT-UNKNOWN")).isEmpty();
	}

	@Test
	void externalIdDifferingOnlyInCaseIsDistinctRequest() {
		ActivationRequest upper = repository
			.saveAndFlush(ActivationRequest.create("EXT-CASE", "CUST-1", "INTERNET-500", NOW));
		ActivationRequest lower = repository
			.saveAndFlush(ActivationRequest.create("ext-case", "CUST-1", "INTERNET-500", NOW));

		assertThat(repository.count()).isEqualTo(2);
		assertThat(repository.findByExternalId("EXT-CASE")).map(ActivationRequest::getId).contains(upper.getId());
		assertThat(repository.findByExternalId("ext-case")).map(ActivationRequest::getId).contains(lower.getId());
	}

}
