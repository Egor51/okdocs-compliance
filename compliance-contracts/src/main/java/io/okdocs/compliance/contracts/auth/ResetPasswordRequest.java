package io.okdocs.compliance.contracts.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank String token,
        @Size(min = 8, max = 100) String newPassword
) {
}
