package io.okdocs.compliance.contracts.scan;

import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Полный отчёт скана. {@code paywallCta} присутствует только в FREE. */
public record ScanReportResponse(
        UUID id,
        String siteUrl,
        String siteDomain,
        ScanStatus status,
        Integer score,
        ScanTier tier,
        UUID parentScanId,
        ScanSummaryDto summary,
        List<FindingDto> findings,
        DiagnosticsDto diagnostics,
        PaywallCtaDto paywallCta,
        Instant createdAt,
        Instant finishedAt
) {
}
