package io.okdocs.compliance.contracts.monitoring;

import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SiteMonitorStatus;

import java.time.Instant;
import java.util.UUID;

public record SiteMonitorDto(
        UUID id,
        String siteUrl,
        String siteDomain,
        ScanJurisdiction jurisdiction,
        SiteMonitorStatus status,
        int intervalDays,
        String timezone,
        boolean notificationsEnabled,
        UUID lastScanId,
        Integer lastScore,
        Instant lastRunAt,
        Instant nextRunAt,
        Instant createdAt
) {
}
