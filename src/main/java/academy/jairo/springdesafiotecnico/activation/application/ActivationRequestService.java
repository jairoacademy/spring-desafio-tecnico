package academy.jairo.springdesafiotecnico.activation.application;

import java.time.Clock;
import java.util.UUID;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import academy.jairo.springdesafiotecnico.activation.infrastructure.ActivationRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivationRequestService {


	private final ActivationRequestRepository repository;

	private final Clock clock;

	public ActivationRequestService(ActivationRequestRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional
	public ActivationRequest create(String externalId, String customerId, String productCode) {
		return repository.save(ActivationRequest.create(externalId, customerId, productCode, clock.instant()));
	}

	@Transactional(readOnly = true)
	public ActivationRequest findById(UUID id) {
		return repository.findById(id).orElseThrow(() -> new ActivationRequestNotFoundException(id));
	}


}
