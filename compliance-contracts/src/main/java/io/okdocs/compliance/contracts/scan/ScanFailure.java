package io.okdocs.compliance.contracts.scan;

import io.okdocs.compliance.contracts.enums.ScanFailureCode;
import io.okdocs.compliance.contracts.enums.ScanFailureStage;
import io.okdocs.compliance.contracts.enums.ScanFetchMode;

import java.util.Objects;
import java.util.UUID;

/**
 * Structured terminal failure exposed by Kafka and scan-status APIs.
 * Exception messages and class names are deliberately not part of this contract.
 */
public record ScanFailure(
        ScanFailureCode code,
        ScanFailureStage stage,
        boolean retryable,
        Integer httpStatus,
        ScanFetchMode fetchMode,
        boolean fallbackAttempted,
        UUID incidentId
) {
    public ScanFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(stage, "stage");
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
    }
}
