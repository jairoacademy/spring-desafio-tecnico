package academy.jairo.springdesafiotecnico.activation.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import academy.jairo.springdesafiotecnico.TestcontainersConfiguration;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

// Chama o serviço, não o HTTP, para isolar a corrida no banco; o pool padrão do Hikari (10) comporta as threads.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ActivationRequestConcurrencyIntegrationTests {

	private static final int THREADS = 10;

	@Autowired
	private ActivationRequestService service;

	@Autowired
	private ActivationRequestRepository repository;

	@Test
	void parallelIdenticalCreationsStoreExactlyOneRequest() throws Exception {
		String externalId = "EXT-RACE-" + UUID.randomUUID();
		CountDownLatch start = new CountDownLatch(1);
		List<CreationResult> results = new ArrayList<>();

		try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
			List<Future<CreationResult>> futures = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					return service.create("sistema-a", externalId, "CUST-RACE", "INTERNET-500");
				}));
			}
			start.countDown();
			// get() relança qualquer exceção das threads e falha o teste
			for (Future<CreationResult> future : futures) {
				results.add(future.get());
			}
		}

		assertThat(repository.findAll()).filteredOn(request -> request.getExternalId().equals(externalId))
			.hasSize(1);
		assertThat(results).filteredOn(CreationResult::created).hasSize(1);
		assertThat(results).extracting(CreationResult::request)
			.extracting(ActivationRequest::getId)
			.containsOnly(results.getFirst().request().getId());
	}

}
