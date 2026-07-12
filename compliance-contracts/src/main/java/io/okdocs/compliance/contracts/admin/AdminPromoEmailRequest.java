package io.okdocs.compliance.contracts.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AdminPromoEmailRequest(
        @NotNull UUID campaignId,
        @Email @NotBlank String email,
        @NotBlank @Size(max = 500) String subject,
        @NotBlank @Size(max = 300) String title,
        @NotBlank @Size(max = 5000) String body,
        @NotBlank String actionUrl,
        String locale
) {
}
