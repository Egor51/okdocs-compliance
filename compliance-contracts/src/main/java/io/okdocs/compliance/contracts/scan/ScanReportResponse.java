package io.okdocs.compliance.contracts.scan;

import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Полный отчёт скана. {@code paywallCta} присутствует только в FREE.
 * <p>
 * {@code jurisdiction} API всегда подставляет из живой сущности скана при выдаче (старые снапшоты
 * в БД поля не содержат) — см. {@code ScanCommandService#fromSnapshot}.
 */
public record ScanReportResponse(
        UUID id,
        String siteUrl,
        String siteDomain,
        ScanJurisdiction jurisdiction,
        ScanStatus status,
        Integer score,
        ScanTier tier,
        UUID parentScanId,
        ScanSummaryDto summary,
        List<FindingDto> findings,
        DiagnosticsDto diagnostics,
        ReportQualityDto quality,
        PaywallCtaDto paywallCta,
        Long durationMs,
        Instant createdAt,
        Instant finishedAt
) {
}
