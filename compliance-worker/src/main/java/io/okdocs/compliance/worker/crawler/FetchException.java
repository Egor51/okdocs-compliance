package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.contracts.scan.ScanFailure;

import java.io.IOException;

/** Typed network failure retained inside the worker and mapped to the public failure contract. */
public final class FetchException extends IOException {

    private final ScanFailure failure;

    public FetchException(ScanFailure failure, String diagnosticMessage) {
        super(diagnosticMessage);
        this.failure = failure;
    }

    public FetchException(ScanFailure failure, String diagnosticMessage, Throwable cause) {
        super(diagnosticMessage, cause);
        this.failure = failure;
    }

    public ScanFailure failure() {
        return failure;
    }
}
