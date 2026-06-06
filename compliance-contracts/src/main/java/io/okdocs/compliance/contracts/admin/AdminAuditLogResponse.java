package io.okdocs.compliance.contracts.admin;

import java.util.List;

/** Пагинированный журнал admin-действий. */
public record AdminAuditLogResponse(
        List<AdminAuditLogDto> items,
        int page,
        int size,
        long total
) {
}
