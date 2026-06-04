package io.okdocs.compliance.contracts.event;

import io.okdocs.compliance.contracts.enums.ScanStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Отчёт сформирован (status = {@code COMPLETED} или {@code PARTIAL}).
 * {@code pagesScanned} = {@code CrawlerDiagnostics.pagesFetched} (единый источник, не пересчитывать).
 */
public record ScanCompletedEvent(
        UUID eventId,
        int schemaVersion,
        UUID scanId,
        Long userId,
        UUID guestId,
        ScanStatus status,
        Integer score,
        int pagesScanned,
        Instant completedAt
) {
}
