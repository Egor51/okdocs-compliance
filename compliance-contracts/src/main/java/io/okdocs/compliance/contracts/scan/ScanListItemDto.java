package io.okdocs.compliance.contracts.scan;

import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;

import java.time.Instant;
import java.util.UUID;

/**
 * Элемент истории сканов. {@code criticalCount}/{@code highCount} — сводка по severity прямо в
 * строке (оценить «насколько плохо» без открытия отчёта). {@code parentScanId} связывает повторные
 * проверки в цепочку.
 */
public record ScanListItemDto(
        UUID id,
        String siteUrl,
        String siteDomain,
        ScanStatus status,
        Integer score,
        ScanTier tier,
        int criticalCount,
        int highCount,
        UUID parentScanId,
        Instant createdAt,
        Instant finishedAt
) {
}
