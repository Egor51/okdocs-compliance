package io.okdocs.compliance.contracts.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Регистрация. Можно вызывать с guest JWT — backend привяжет guest-сканы к новому {@code userId}.
 */
public record RegisterRequest(
        @Email @NotBlank String email,
        @Size(min = 8, max = 100) String password,
        String name,
        Boolean consentToProcessing
) {
}
