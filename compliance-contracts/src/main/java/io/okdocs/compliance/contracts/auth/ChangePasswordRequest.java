package io.okdocs.compliance.contracts.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Смена пароля в кабинете: проверяется старый, устанавливается новый. */
public record ChangePasswordRequest(
        @NotBlank String oldPassword,
        @Size(min = 8, max = 100) String newPassword
) {
}
