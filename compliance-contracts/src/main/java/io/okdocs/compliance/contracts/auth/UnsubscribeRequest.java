package io.okdocs.compliance.contracts.auth;

import jakarta.validation.constraints.NotBlank;

public record UnsubscribeRequest(@NotBlank String token) {
}
