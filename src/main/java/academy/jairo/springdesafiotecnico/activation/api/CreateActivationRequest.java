package academy.jairo.springdesafiotecnico.activation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Componentes chegam nulos do JSON quando ausentes; @Valid barra a requisição antes de qualquer uso.
public record CreateActivationRequest(

		@NotBlank(message = "é obrigatório")
		@Size(max = 64, message = "deve ter no máximo 64 caracteres")
		@Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "deve conter apenas letras, dígitos, '-' e '_'")
		String externalId,

		@NotBlank(message = "é obrigatório")
		@Size(max = 64, message = "deve ter no máximo 64 caracteres")
		@Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "deve conter apenas letras, dígitos, '-' e '_'")
		String customerId,

		@NotBlank(message = "é obrigatório")
		@Size(max = 50, message = "deve ter no máximo 50 caracteres")
		@Pattern(regexp = "^[A-Z0-9_-]+$", message = "deve conter apenas letras maiúsculas, dígitos, '-' e '_'")
		String productCode) {
}
