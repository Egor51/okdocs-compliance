package io.okdocs.compliance.contracts.remediation;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Публичная заявка со страницы услуги доработки сайта. */
public record RemediationLeadRequest(
        @NotBlank @Size(max = 2048) String siteUrl,
        @NotBlank @Size(min = 2, max = 100) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 40) String phone,
        @NotBlank @Pattern(regexp = "(?i)ru|en") String locale,
        @AssertTrue(message = "Требуется согласие на обработку персональных данных") boolean consent
) {
}
