package academy.jairo.springdesafiotecnico.activation.api;

import java.net.URI;
import java.util.UUID;

import academy.jairo.springdesafiotecnico.activation.application.ActivationRequestService;
import academy.jairo.springdesafiotecnico.activation.application.CreationResult;
import academy.jairo.springdesafiotecnico.activation.domain.ActivationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
		CreationResult result = service.create(body.externalId(), body.customerId(), body.productCode());
		ActivationRequestResponse response = ActivationRequestResponse.from(result.request());
		if (!result.created()) {
			// retry idêntico: devolve o registro existente, com o status atual
			return ResponseEntity.ok(response);
		}
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{id}")
			.buildAndExpand(response.id())
			.toUri();
		return ResponseEntity.created(location).body(response);
	}

	@GetMapping("/{id}")
	ActivationRequestResponse findById(@PathVariable UUID id) {
		return ActivationRequestResponse.from(service.findById(id));
	}

	@GetMapping
	PageResponse<ActivationRequestResponse> list(
			@RequestParam(required = false) @Nullable ActivationStatus status,

			@RequestParam(required = false)
			@Size(max = 64, message = "deve ter no máximo 64 caracteres")
			@Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "deve conter apenas letras, dígitos, '-' e '_'")
			@Nullable String customerId,

			@RequestParam(defaultValue = "0")
			@Min(value = 0, message = "deve ser maior ou igual a 0")
			int page,

			@RequestParam(defaultValue = "20")
			@Min(value = 1, message = "deve ser maior ou igual a 1")
			@Max(value = 100, message = "deve ser menor ou igual a 100")
			int size) {
		return PageResponse.from(service.findAll(status, customerId, page, size), ActivationRequestResponse::from);
	}

}
