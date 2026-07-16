package io.okdocs.compliance.contracts.monitoring;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record UpdateSiteMonitorRequest(
        @Min(2) @Max(3) int intervalDays,
        boolean notificationsEnabled
) {
}
