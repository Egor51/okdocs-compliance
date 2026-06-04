package io.okdocs.compliance.contracts.scan;

import io.okdocs.compliance.contracts.enums.ScanStatus;

import java.util.UUID;

/** Ответ на запуск скана и опрос прогресса. */
public record ScanStatusResponse(
        UUID id,
        ScanStatus status,
        String progressStep,
        int progressPct,
        String reportUrl,
        String errorMessage
) {
}
