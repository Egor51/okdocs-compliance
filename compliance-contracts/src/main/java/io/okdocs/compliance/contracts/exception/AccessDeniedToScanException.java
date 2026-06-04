package io.okdocs.compliance.contracts.exception;

import java.util.UUID;

/** Скан принадлежит другому владельцу — owner-check не пройден (→ HTTP 403). */
public class AccessDeniedToScanException extends RuntimeException {

    private final UUID scanId;

    public AccessDeniedToScanException(UUID scanId) {
        super("Access denied to scan: " + scanId);
        this.scanId = scanId;
    }

    public UUID getScanId() {
        return scanId;
    }
}
