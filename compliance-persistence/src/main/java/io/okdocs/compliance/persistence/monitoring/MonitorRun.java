package io.okdocs.compliance.persistence.monitoring;

import io.okdocs.compliance.contracts.enums.MonitorRunStatus;
import io.okdocs.compliance.contracts.enums.MonitorTrigger;
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
}
