package io.okdocs.compliance.persistence.monitoring;

import io.okdocs.compliance.contracts.enums.MonitorRunStatus;
import io.okdocs.compliance.contracts.enums.MonitorTrigger;
import io.okdocs.compliance.contracts.enums.ScanFailureCode;
import io.okdocs.compliance.contracts.enums.ScanFailureStage;
import io.okdocs.compliance.contracts.enums.ScanFetchMode;
import io.okdocs.compliance.contracts.scan.ScanFailure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "monitor_runs")
@Getter
@Setter
@NoArgsConstructor
public class MonitorRun {

    @Id
    private UUID id;

    @Column(name = "monitor_id", nullable = false)
    private UUID monitorId;

    @Column(name = "scan_id")
    private UUID scanId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MonitorTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MonitorRunStatus status;

    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;

    @Column(name = "previous_score")
    private Integer previousScore;

    @Column(name = "current_score")
    private Integer currentScore;

    @Column(name = "new_findings")
    private Integer newFindings;

    @Column(name = "resolved_findings")
    private Integer resolvedFindings;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 50)
    private ScanFailureCode failureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_stage", length = 30)
    private ScanFailureStage failureStage;

    @Column(name = "failure_retryable")
    private Boolean failureRetryable;

    @Column(name = "failure_http_status")
    private Integer failureHttpStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_fetch_mode", length = 20)
    private ScanFetchMode failureFetchMode;

    @Column(name = "failure_fallback_attempted")
    private Boolean failureFallbackAttempted;

    @Column(name = "failure_incident_id")
    private UUID failureIncidentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }

    public void setFailure(ScanFailure failure) {
        if (failure == null) {
            failureCode = null;
            failureStage = null;
            failureRetryable = null;
            failureHttpStatus = null;
            failureFetchMode = null;
            failureFallbackAttempted = null;
            failureIncidentId = null;
            return;
        }
        failureCode = failure.code();
        failureStage = failure.stage();
        failureRetryable = failure.retryable();
        failureHttpStatus = failure.httpStatus();
        failureFetchMode = failure.fetchMode();
        failureFallbackAttempted = failure.fallbackAttempted();
        failureIncidentId = failure.incidentId();
    }

    public ScanFailure failure() {
        if (failureCode == null) {
            return null;
        }
        return new ScanFailure(
                failureCode,
                failureStage == null ? ScanFailureStage.UNKNOWN : failureStage,
                Boolean.TRUE.equals(failureRetryable),
                failureHttpStatus,
                failureFetchMode,
                Boolean.TRUE.equals(failureFallbackAttempted),
                failureIncidentId);
    }
}
