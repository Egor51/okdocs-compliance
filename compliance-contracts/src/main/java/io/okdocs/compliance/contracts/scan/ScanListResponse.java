package io.okdocs.compliance.contracts.scan;

import java.util.List;

/** Пагинированная история сканов. */
public record ScanListResponse(
        List<ScanListItemDto> items,
        int page,
        int size,
        long total
) {
}
