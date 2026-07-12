package io.okdocs.compliance.mail.model;

public record MailDeliveryResult(Outcome outcome, String error) {

    public enum Outcome { DELIVERED, SIMULATED, RETRYABLE_FAILURE, PERMANENT_FAILURE }

    public static MailDeliveryResult delivered() {
        return new MailDeliveryResult(Outcome.DELIVERED, null);
    }

    public static MailDeliveryResult simulated() {
        return new MailDeliveryResult(Outcome.SIMULATED, null);
    }

    public static MailDeliveryResult retryable(String error) {
        return new MailDeliveryResult(Outcome.RETRYABLE_FAILURE, error);
    }

    public static MailDeliveryResult permanent(String error) {
        return new MailDeliveryResult(Outcome.PERMANENT_FAILURE, error);
    }
}
