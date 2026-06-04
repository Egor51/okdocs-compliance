package io.okdocs.compliance.contracts.exception;

/** Бизнес-валидация запроса не прошла (помимо Jakarta Validation). */
public class ComplianceValidationException extends RuntimeException {

    public ComplianceValidationException(String message) {
        super(message);
    }
}
