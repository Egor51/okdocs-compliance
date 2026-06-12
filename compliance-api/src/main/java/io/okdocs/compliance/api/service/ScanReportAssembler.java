package io.okdocs.compliance.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.contracts.scan.AffectedPageDto;
import io.okdocs.compliance.contracts.scan.DiagnosticsDto;
import io.okdocs.compliance.contracts.scan.FindingDto;
import io.okdocs.compliance.contracts.scan.PaywallCtaDto;
import io.okdocs.compliance.contracts.scan.PositiveCheckDto;
import io.okdocs.compliance.contracts.scan.ReportQualityDto;
import io.okdocs.compliance.contracts.scan.RuleOutcomeDto;
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
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern FINE_NUMBER_PATTERN = Pattern.compile("(\\d[\\d\\s]*)");

    private final ComplianceApiProperties properties;
    private final ObjectMapper objectMapper;

    public ScanReportResponse assemble(ComplianceScan scan, List<ComplianceFinding> findings) {
        ScanTier tier = effectiveTier(scan);
        boolean premium = tier == ScanTier.PREMIUM;
        Map<String, List<ComplianceFinding>> groupedFindings = groupByCode(findings);
        List<ComplianceFinding> representativeFindings = groupedFindings.values().stream()
                .map(ScanReportAssembler::representative)
                .toList();
        List<FindingDto> findingDtos = groupedFindings.values().stream()
                .map(group -> toDto(representative(group), group, premium))
                .toList();
        ScanSummaryDto summary = summarize(representativeFindings);
        DiagnosticsDto diagnostics = diagnostics(scan);
        ReportQualityDto quality = quality(diagnostics);
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
                diagnostics,
                quality,
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

    private static Map<String, List<ComplianceFinding>> groupByCode(List<ComplianceFinding> findings) {
        Map<String, List<ComplianceFinding>> byCode = new LinkedHashMap<>();
        if (findings == null || findings.isEmpty()) {
            return byCode;
        }
        for (ComplianceFinding finding : findings) {
            if (finding != null && finding.getCode() != null) {
                byCode.computeIfAbsent(finding.getCode(), ignored -> new ArrayList<>()).add(finding);
            }
        }
        return byCode;
    }

    private static ComplianceFinding representative(List<ComplianceFinding> findings) {
        return findings.stream()
                .reduce(ScanReportAssembler::betterRepresentative)
                .orElseThrow();
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

    private FindingDto toDto(ComplianceFinding f, List<ComplianceFinding> group, boolean premium) {
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
                premium ? splitSignals(f.getMatchedSignals()) : null,
                premium ? affectedPages(group) : List.of());
    }

    private List<AffectedPageDto> affectedPages(List<ComplianceFinding> group) {
        if (group == null || group.isEmpty()) {
            return List.of();
        }
        return group.stream()
                .filter(f -> hasText(f.getPageUrl()) || hasText(f.getSourceUrl()))
                .map(f -> new AffectedPageDto(
                        firstNonBlank(f.getPageUrl(), f.getSourceUrl()),
                        f.getEvidence(),
                        f.getSourceType(),
                        f.getConfidence(),
                        f.getVerificationStatus(),
                        f.getEvidenceType(),
                        splitSignals(f.getMatchedSignals())))
                .toList();
    }

    private static String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
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

    private String totalFine(List<ComplianceFinding> findings) {
        long min = 0;
        long max = 0;
        for (ComplianceFinding finding : findings) {
            long[] range = fineRange(finding.getFineAmount());
            min += range[0];
            max += range[1];
        }
        if (max == 0) {
            return null;
        }
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        DecimalFormat fmt = new DecimalFormat("#,##0", symbols);
        return "от " + fmt.format(min) + " до " + fmt.format(max) + " ₽";
    }

    private static long[] fineRange(String fineAmount) {
        if (fineAmount == null || fineAmount.isBlank()) {
            return new long[]{0, 0};
        }
        Matcher matcher = FINE_NUMBER_PATTERN.matcher(fineAmount);
        long min = Long.MAX_VALUE;
        long max = 0;
        while (matcher.find()) {
            String raw = matcher.group(1).replaceAll("\\s", "");
            if (raw.isEmpty()) {
                continue;
            }
            try {
                long value = Long.parseLong(raw);
                if (value < 1000) {
                    continue;
                }
                min = Math.min(min, value);
                max = Math.max(max, value);
            } catch (NumberFormatException ignored) {
                // Fine text is free-form; non-parseable fragments are ignored.
            }
        }
        if (min == Long.MAX_VALUE) {
            return new long[]{0, 0};
        }
        return new long[]{min, max};
    }

    private DiagnosticsDto diagnostics(ComplianceScan scan) {
        if (scan.getDiagnosticsJson() == null || scan.getDiagnosticsJson().isBlank()) {
            return new DiagnosticsDto(0, scan.getPagesScanned(), 0, false, List.of(), List.of());
        }
        try {
            return objectMapper.readValue(scan.getDiagnosticsJson(), DiagnosticsDto.class);
        } catch (Exception e) {
            log.warn("Не удалось распарсить diagnosticsJson скана {}: {}", scan.getId(), e.getMessage());
            return new DiagnosticsDto(0, scan.getPagesScanned(), 0, false, List.of(), List.of());
        }
    }

    private static ReportQualityDto quality(DiagnosticsDto diagnostics) {
        if (diagnostics == null || diagnostics.ruleOutcomes() == null || diagnostics.ruleOutcomes().isEmpty()) {
            return new ReportQualityDto(0, 0, 0, List.of());
        }
        int passed = 0;
        int failed = 0;
        int notEvaluated = 0;
        List<PositiveCheckDto> positiveChecks = new ArrayList<>();
        for (RuleOutcomeDto outcome : diagnostics.ruleOutcomes()) {
            if (outcome == null || outcome.status() == null) {
                continue;
            }
            switch (outcome.status()) {
                case "PASSED" -> {
                    passed++;
                    PositiveCheckDto positive = positiveCheck(outcome);
                    if (positive != null) {
                        positiveChecks.add(positive);
                    }
                }
                case "FAILED" -> failed++;
                case "NOT_EVALUATED" -> notEvaluated++;
                default -> {
                    // unknown future status: keep report readable, don't count it as green
                }
            }
        }
        return new ReportQualityDto(passed, failed, notEvaluated, List.copyOf(positiveChecks));
    }

    private static PositiveCheckDto positiveCheck(RuleOutcomeDto outcome) {
        if (!hasText(outcome.code())) {
            return null;
        }
        return switch (outcome.code()) {
            case "NO_PRIVACY_POLICY" -> new PositiveCheckDto(
                    outcome.code(),
                    "Политика обработки персональных данных найдена",
                    outcome.category(),
                    "На сайте найдена страница или ссылка на политику обработки персональных данных.");
            case "NO_COOKIE_CONSENT" -> new PositiveCheckDto(
                    outcome.code(),
                    "Механизм cookie-согласия обнаружен",
                    outcome.category(),
                    "На сайте найден cookie-баннер или иной механизм запроса согласия.");
            case "UNPROTECTED_DATA_FORMS" -> new PositiveCheckDto(
                    outcome.code(),
                    "Небезопасные формы с персональными данными не обнаружены",
                    outcome.category(),
                    "Сканер не нашёл формы с персональными данными, отправляемые по незащищённому HTTP.");
            case "CONSENT_DEFAULT_CHECKED" -> new PositiveCheckDto(
                    outcome.code(),
                    "Предотмеченное согласие не обнаружено",
                    outcome.category(),
                    "Сканер не нашёл чекбоксы согласия, отмеченные по умолчанию.");
            case "THIRD_PARTY_TRACKERS" -> new PositiveCheckDto(
                    outcome.code(),
                    "Нераскрытые сторонние трекеры не обнаружены",
                    outcome.category(),
                    "Сканер не выявил сторонние трекеры, не раскрытые в политике обработки ПДн.");
            case "HOSTING_OUTSIDE_RU_DETECTED" -> new PositiveCheckDto(
                    outcome.code(),
                    "Сервер сайта расположен в Российской Федерации",
                    outcome.category(),
                    "GeoIP-проверка основного домена определила страну хостинга как RU.");
            default -> null;
        };
    }

    private PaywallCtaDto paywallCta() {
        var cta = properties.paywallCta();
        if (cta == null) {
            return null;
        }
        return new PaywallCtaDto(cta.title(), cta.text(), cta.actionUrl());
    }
}
