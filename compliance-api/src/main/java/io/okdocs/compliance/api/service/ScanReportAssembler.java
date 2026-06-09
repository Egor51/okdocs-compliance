package io.okdocs.compliance.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
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

import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        ScanTier tier = effectiveTier(scan);
        boolean premium = tier == ScanTier.PREMIUM;
        List<ComplianceFinding> representativeFindings = deduplicateByCode(findings);
        List<FindingDto> findingDtos = representativeFindings.stream().map(f -> toDto(f, premium)).toList();
        ScanSummaryDto summary = summarize(representativeFindings);
        PaywallCtaDto cta = premium ? null : paywallCta();

        return new ScanReportResponse(
                scan.getId(),
                scan.getSiteUrl(),
                scan.getSiteDomain(),
                scan.getStatus(),
                scan.getScore(),
                tier,
                scan.getParentScanId(),
                summary,
                findingDtos,
                diagnostics(scan),
                cta,
                durationMs(scan),
                scan.getCreatedAt(),
                scan.getFinishedAt());
    }

    private static Long durationMs(ComplianceScan scan) {
        if (scan.getDurationMs() != null) {
            return scan.getDurationMs();
        }
        if (scan.getStartedAt() == null || scan.getFinishedAt() == null) {
            return null;
        }
        return Duration.between(scan.getStartedAt(), scan.getFinishedAt()).toMillis();
    }

    /**
     * CABINET_PREMIUM is paid at scan start via balance debit, so its report is premium even if an
     * older row still has the historical default tier=FREE.
     */
    private static ScanTier effectiveTier(ComplianceScan scan) {
        if (scan.getTier() == ScanTier.PREMIUM || scan.getKind() == ScanKind.CABINET_PREMIUM) {
            return ScanTier.PREMIUM;
        }
        return ScanTier.FREE;
    }

    private static List<ComplianceFinding> deduplicateByCode(List<ComplianceFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        Map<String, ComplianceFinding> byCode = new LinkedHashMap<>();
        for (ComplianceFinding finding : findings) {
            if (finding == null || finding.getCode() == null) {
                continue;
            }
            byCode.merge(finding.getCode(), finding, ScanReportAssembler::betterRepresentative);
        }
        return new ArrayList<>(byCode.values());
    }

    private static ComplianceFinding betterRepresentative(ComplianceFinding a, ComplianceFinding b) {
        int confidence = Double.compare(confidenceScore(b), confidenceScore(a));
        if (confidence != 0) {
            return confidence > 0 ? b : a;
        }
        int verification = Integer.compare(verificationRank(b), verificationRank(a));
        if (verification != 0) {
            return verification > 0 ? b : a;
        }
        int evidence = Integer.compare(completenessRank(b), completenessRank(a));
        return evidence > 0 ? b : a;
    }

    private static double confidenceScore(ComplianceFinding finding) {
        return finding.getConfidence() == null ? -1.0 : finding.getConfidence();
    }

    private static int verificationRank(ComplianceFinding finding) {
        VerificationStatus status = finding.getVerificationStatus();
        if (status == VerificationStatus.CONFIRMED) {
            return 3;
        }
        if (status == VerificationStatus.DETECTED) {
            return 2;
        }
        if (status == VerificationStatus.UNVERIFIED) {
            return 1;
        }
        return 0;
    }

    private static int completenessRank(ComplianceFinding finding) {
        int rank = 0;
        if (hasText(finding.getEvidence())) {
            rank++;
        }
        if (hasText(finding.getSourceUrl())) {
            rank++;
        }
        if (hasText(finding.getMatchedSignals())) {
            rank++;
        }
        return rank;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
