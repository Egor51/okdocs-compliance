package io.okdocs.compliance.contracts.admin;

import java.util.List;

/** Пагинированный список юзеров. */
public record AdminUserListResponse(
        List<AdminUserListItem> items,
        int page,
        int size,
        long total
) {
}
