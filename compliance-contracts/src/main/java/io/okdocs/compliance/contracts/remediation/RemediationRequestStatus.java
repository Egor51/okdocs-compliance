package io.okdocs.compliance.contracts.remediation;

/** Жизненный цикл заявки на доработку полного отчёта. */
public enum RemediationRequestStatus {
    NEW,
    CONTACTED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
