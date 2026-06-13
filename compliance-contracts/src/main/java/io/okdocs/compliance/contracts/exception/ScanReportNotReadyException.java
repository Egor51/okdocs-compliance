package io.okdocs.compliance.contracts.exception;

import java.util.UUID;

/** Снапшот отчёта ещё не сформирован (→ HTTP 409). */
public class ScanReportNotReadyException extends RuntimeException {

    private final UUID scanId;

    public ScanReportNotReadyException(UUID scanId) {
        super("Scan report is not ready: " + scanId);
        this.scanId = scanId;
    }

    public UUID getScanId() {
        return scanId;
    }
}
