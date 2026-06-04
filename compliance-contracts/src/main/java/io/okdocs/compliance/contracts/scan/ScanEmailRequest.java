package io.okdocs.compliance.contracts.scan;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Сохранение email + согласий для отправки отчёта. */
public record ScanEmailRequest(
        @Email @NotBlank String email,
        boolean consentToProcessing,
        boolean consentToMarketing
) {
}
