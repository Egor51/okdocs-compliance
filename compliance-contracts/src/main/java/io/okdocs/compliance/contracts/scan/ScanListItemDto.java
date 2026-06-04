package io.okdocs.compliance.contracts.scan;

import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;

import java.time.Instant;
import java.util.UUID;

/** Элемент истории сканов. */
public record ScanListItemDto(
        UUID id,
        String siteUrl,
        String siteDomain,
        ScanStatus status,
        Integer score,
        ScanTier tier,
        Instant createdAt,
        Instant finishedAt
) {
}
