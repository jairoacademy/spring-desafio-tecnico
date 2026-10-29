package academy.jairo.springdesafiotecnico.activation.infrastructure;

import java.util.UUID;

import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ActivationRequestRepository
		extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

}
