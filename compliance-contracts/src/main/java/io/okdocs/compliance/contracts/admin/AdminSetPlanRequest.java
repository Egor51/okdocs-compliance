package io.okdocs.compliance.contracts.admin;

import io.okdocs.compliance.contracts.enums.UserPlan;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Смена тарифа юзера админом. */
public record AdminSetPlanRequest(
        @NotNull Long userId,
        @NotNull UserPlan plan,
        @NotBlank String reason
) {
}
