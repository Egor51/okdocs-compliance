package io.okdocs.compliance.contracts.admin;

import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.contracts.enums.UserStatus;

import java.time.Instant;

/** Строка списка юзеров в админке. */
public record AdminUserListItem(
        Long id,
        String email,
        String name,
        UserPlan plan,
        UserStatus status,
        int available,
        long totalScans,
        Instant createdAt,
        Instant lastLoginAt
) {
}
