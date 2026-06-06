package io.okdocs.compliance.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.scan.DiagnosticsDto;
import io.okdocs.compliance.contracts.scan.FindingDto;
import io.okdocs.compliance.contracts.scan.PaywallCtaDto;
import io.okdocs.compliance.contracts.scan.ScanReportResponse;
import io.okdocs.compliance.contracts.scan.ScanSummaryDto;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Сборка {@link ScanReportResponse} из {@link ComplianceScan} + findings (§4.2).
 * Premium-поля ({@code explanation}/{@code recommendation}/{@code evidence}/{@code sourceUrl})
 * маскируются для FREE-отчёта; для FREE добавляется {@code paywallCta}. Диагностика
 * десериализуется из {@code diagnosticsJson}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanReportAssembler {

    private final ComplianceApiProperties properties;
    private final ObjectMapper objectMapper;

    public ScanReportResponse assemble(ComplianceScan scan, List<ComplianceFinding> findings) {
        boolean premium = scan.getTier() == ScanTier.PREMIUM;
        List<FindingDto> findingDtos = findings.stream().map(f -> toDto(f, premium)).toList();
        ScanSummaryDto summary = summarize(findings);
        PaywallCtaDto cta = premium ? null : paywallCta();

        return new ScanReportResponse(
                scan.getId(),
                scan.getSiteUrl(),
                scan.getSiteDomain(),
                scan.getStatus(),
                scan.getScore(),
                scan.getTier(),
                scan.getParentScanId(),
                summary,
                findingDtos,
                diagnostics(scan),
                cta,
                scan.getCreatedAt(),
                scan.getFinishedAt());
    }

    private FindingDto toDto(ComplianceFinding f, boolean premium) {
        return new FindingDto(
                f.getCode(),
                f.getSeverity(),
                f.getCategory(),
                f.getTitle(),
                f.getFineAmount(),
                f.getLegalBasis(),
                premium ? f.getExplanation() : null,
                premium ? f.getRecommendation() : null,
                premium ? f.getEvidence() : null,
                premium ? f.getSourceUrl() : null,
                premium ? f.getSourceType() : null,
                f.getConfidence(),
                f.getVerificationStatus(),
                f.getEvidenceType(),
                premium ? splitSignals(f.getMatchedSignals()) : null);
    }

    private static List<String> splitSignals(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private ScanSummaryDto summarize(List<ComplianceFinding> findings) {
        int critical = 0, high = 0, medium = 0, low = 0;
        for (ComplianceFinding f : findings) {
            switch (f.getSeverity()) {
                case CRITICAL -> critical++;
                case HIGH -> high++;
                case MEDIUM -> medium++;
                case LOW -> low++;
            }
        }
        return new ScanSummaryDto(critical, high, medium, low, totalFine(findings));
    }

    /** В MVP суммарный штраф — простая конкатенация по верхним findings; точный расчёт — позже. */
    private String totalFine(List<ComplianceFinding> findings) {
        boolean any = findings.stream().anyMatch(f -> f.getSeverity() == FindingSeverity.CRITICAL
                || f.getSeverity() == FindingSeverity.HIGH);
        return any ? "до 500 000 ₽" : null;
    }

    private DiagnosticsDto diagnostics(ComplianceScan scan) {
        if (scan.getDiagnosticsJson() == null || scan.getDiagnosticsJson().isBlank()) {
            return new DiagnosticsDto(0, scan.getPagesScanned(), 0, false, List.of());
        }
        try {
            return objectMapper.readValue(scan.getDiagnosticsJson(), DiagnosticsDto.class);
        } catch (Exception e) {
            log.warn("Не удалось распарсить diagnosticsJson скана {}: {}", scan.getId(), e.getMessage());
            return new DiagnosticsDto(0, scan.getPagesScanned(), 0, false, List.of());
        }
    }

    private PaywallCtaDto paywallCta() {
        var cta = properties.paywallCta();
        if (cta == null) {
            return null;
        }
        return new PaywallCtaDto(cta.title(), cta.text(), cta.actionUrl());
    }
}
