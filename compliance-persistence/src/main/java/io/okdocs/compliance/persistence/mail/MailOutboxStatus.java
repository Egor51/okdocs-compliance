package io.okdocs.compliance.persistence.mail;

public enum MailOutboxStatus {
    PENDING,
    SENT,
    DEAD,
    SIMULATED,
    CANCELLED
}
