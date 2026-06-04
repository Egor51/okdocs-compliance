package io.okdocs.compliance.contracts.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Блокировка/разблокировка юзера админом. */
public record AdminBlockUserRequest(
        @NotNull Long userId,
        boolean block,
        @NotBlank String reason
) {
}
