package io.okdocs.compliance.contracts.exception;

import java.util.UUID;

/** Скан не найден (→ HTTP 404). */
public class ScanNotFoundException extends RuntimeException {

    private final UUID scanId;

    public ScanNotFoundException(UUID scanId) {
        super("Scan not found: " + scanId);
        this.scanId = scanId;
    }

    public UUID getScanId() {
        return scanId;
    }
}
