package io.okdocs.compliance.contracts.admin;

import io.okdocs.compliance.contracts.enums.AdminActionType;

import java.time.Instant;
import java.util.UUID;

/** Запись журнала действий админа. */
public record AdminAuditLogDto(
        UUID id,
        Long adminUserId,
        AdminActionType action,
        Long targetUserId,
        String reason,
        String detailsJson,
        Instant createdAt
) {
}
