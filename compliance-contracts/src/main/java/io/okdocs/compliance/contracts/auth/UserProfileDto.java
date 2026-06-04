package io.okdocs.compliance.contracts.auth;

import io.okdocs.compliance.contracts.enums.UserRole;
import io.okdocs.compliance.contracts.enums.UserStatus;

/** Профиль зарегистрированного пользователя. */
public record UserProfileDto(
        Long id,
        String email,
        String name,
        UserRole role,
        UserStatus status
) {
}
