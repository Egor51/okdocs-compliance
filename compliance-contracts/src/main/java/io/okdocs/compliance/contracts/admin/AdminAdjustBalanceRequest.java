package io.okdocs.compliance.contracts.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Ручная корректировка баланса админом. {@code amount} — ± сканов. */
public record AdminAdjustBalanceRequest(
        @NotNull Long userId,
        int amount,
        @NotBlank String reason
) {
}
