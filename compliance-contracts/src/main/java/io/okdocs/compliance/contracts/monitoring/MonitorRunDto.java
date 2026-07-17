package io.okdocs.compliance.contracts.monitoring;

import io.okdocs.compliance.contracts.enums.MonitorRunStatus;
import io.okdocs.compliance.contracts.enums.MonitorTrigger;

import java.time.Instant;
import java.util.UUID;

public record MonitorRunDto(
        UUID id,
        UUID monitorId,
        UUID scanId,
        MonitorTrigger trigger,
        MonitorRunStatus status,
        Instant scheduledFor,
        Integer previousScore,
        Integer currentScore,
        Integer newFindings,
        Integer resolvedFindings,
        String errorMessage,
        Instant createdAt,
        Instant finishedAt
) {
}
