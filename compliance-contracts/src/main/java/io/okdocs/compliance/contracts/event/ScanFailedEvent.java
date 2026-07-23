package io.okdocs.compliance.contracts.event;

import io.okdocs.compliance.contracts.scan.ScanFailure;

import java.time.Instant;
import java.util.UUID;

/** Краулер упал, отчёт не создан (status = {@code FAILED}). */
public record ScanFailedEvent(
        UUID eventId,
        int schemaVersion,
        UUID scanId,
        Long userId,
        UUID guestId,
        String errorMessage,
        ScanFailure failure,
        Instant failedAt
) {
}
