package io.okdocs.compliance.contracts.exception;

/** Превышен лимит запросов (→ HTTP 429). */
public class ComplianceRateLimitException extends RuntimeException {

    public ComplianceRateLimitException(String message) {
        super(message);
    }
}
