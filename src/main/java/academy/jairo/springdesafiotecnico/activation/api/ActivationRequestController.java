package academy.jairo.springdesafiotecnico.activation.api;

import java.net.URI;
import java.util.UUID;

import academy.jairo.springdesafiotecnico.activation.application.ActivationRequestService;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/requests")
class ActivationRequestController {

	private final ActivationRequestService service;

	ActivationRequestController(ActivationRequestService service) {
		this.service = service;
	}

	@PostMapping
	ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest body) {
		ActivationRequest created = service.create(body.externalId(), body.customerId(), body.productCode());
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{id}")
			.buildAndExpand(created.getId())
			.toUri();
		return ResponseEntity.created(location).body(ActivationRequestResponse.from(created));
	}

	@GetMapping("/{id}")
	ActivationRequestResponse findById(@PathVariable UUID id) {
		return ActivationRequestResponse.from(service.findById(id));
	}


}
