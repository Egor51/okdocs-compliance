package io.okdocs.compliance.contracts.enums;

/** Result of one scheduled or manual monitoring execution. */
public enum MonitorRunStatus {
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
    SKIPPED
}
