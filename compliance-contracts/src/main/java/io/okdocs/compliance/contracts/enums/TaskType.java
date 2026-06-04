package io.okdocs.compliance.contracts.enums;

/** Тип задачи планировщика. */
public enum TaskType {
    SCAN_SITE,
    RETRY_SCAN,
    GENERATE_PDF,
    SEND_REPORT_EMAIL,
    MONTHLY_MONITORING
}
