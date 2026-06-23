package io.okdocs.compliance.contracts.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Обмен one-time кода автологина (из OAuth-redirect, F.8) на пару JWT+refresh.
 */
public record OAuthExchangeRequest(
        @NotBlank String code
) {
}
