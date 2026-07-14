package io.okdocs.compliance.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.contracts.scan.ScanReportResponse;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тест переноса сборки отчёта в worker: премиум-снапшот полный, free маскирует premium-поля,
 * {@code paywallCta=null} в обоих (его дописывает API), билдер тотален на пустых findings.
 */
class ScanReportBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ScanReportBuilder builder = new ScanReportBuilder(objectMapper);

    @Test
    void buildsPremiumFullAndFreeMaskedBothWithoutPaywallCta() throws Exception {
        ComplianceScan scan = scan();
        ComplianceFinding f = finding(scan.getId());

        ScanReportSnapshots snapshots = builder.build(scan, List.of(f));

        ScanReportResponse premium = objectMapper.readValue(snapshots.premiumJson(), ScanReportResponse.class);
        ScanReportResponse free = objectMapper.readValue(snapshots.freeJson(), ScanReportResponse.class);

        assertThat(premium.tier()).isEqualTo(ScanTier.PREMIUM);
        assertThat(premium.findings()).hasSize(1);
        assertThat(premium.findings().get(0).explanation()).isEqualTo("because reasons");
        assertThat(premium.findings().get(0).evidence()).isEqualTo("found cookie");
        assertThat(premium.findings().get(0).affectedPages()).hasSize(1);
        assertThat(premium.paywallCta()).isNull();

        assertThat(free.tier()).isEqualTo(ScanTier.FREE);
        assertThat(free.findings().get(0).explanation()).isNull();
        assertThat(free.findings().get(0).evidence()).isNull();
        assertThat(free.findings().get(0).affectedPages()).isEmpty();
        assertThat(free.paywallCta()).isNull(); // API дописывает CTA, не worker
        // Не-premium summary одинаков в обоих
        assertThat(free.summary().critical()).isEqualTo(premium.summary().critical());
    }

    @Test
    void isTotalOnEmptyFindings() throws Exception {
        ComplianceScan scan = scan();

        ScanReportSnapshots snapshots = builder.build(scan, List.of());

        ScanReportResponse premium = objectMapper.readValue(snapshots.premiumJson(), ScanReportResponse.class);
        assertThat(premium.findings()).isEmpty();
        assertThat(premium.summary().totalPotentialFine()).isNull();
    }

    @Test
    void groupsFindingsAndBuildsAffectedPagesAndQuality() throws Exception {
        ComplianceScan scan = scan();
        scan.setDiagnosticsJson("""
                {
                  "pagesAttempted": 2,
                  "pagesFetched": 2,
                  "pagesFailed": 0,
                  "crawlerTimedOut": false,
                  "ruleErrors": [],
                  "ruleOutcomes": [
                    {"code": "NO_PRIVACY_POLICY", "status": "PASSED", "title": "old", "severity": "HIGH", "category": "DOCUMENTS",
                     "positiveTitle": "Политика обработки персональных данных найдена",
                     "positiveMessage": "На сайте найдена страница или ссылка на политику обработки персональных данных."},
                    {"code": "THIRD_PARTY_TRACKERS", "status": "FAILED", "title": "tracker", "severity": "MEDIUM", "category": "TRACKERS"},
                    {"code": "RKN_REGISTRY_NOT_VERIFIED", "status": "NOT_EVALUATED", "title": "rkn", "severity": "HIGH", "category": "DOCUMENTS"}
                  ]
                }
                """);

        ScanReportSnapshots snapshots = builder.build(scan, List.of(
                finding("THIRD_PARTY_TRACKERS", FindingSeverity.MEDIUM, 0.70,
                        "https://site.ru/a", "tracker-a", VerificationStatus.DETECTED),
                finding("THIRD_PARTY_TRACKERS", FindingSeverity.MEDIUM, 0.90,
                        "https://site.ru/b", "tracker-b", VerificationStatus.UNVERIFIED),
                finding("RKN_REGISTRY_NOT_VERIFIED", FindingSeverity.HIGH, null,
                        "https://site.ru", "rkn", VerificationStatus.UNVERIFIED)));

        ScanReportResponse premium = objectMapper.readValue(snapshots.premiumJson(), ScanReportResponse.class);

        assertThat(premium.findings()).hasSize(2);
        assertThat(premium.summary().medium()).isEqualTo(1);
        assertThat(premium.summary().high()).isZero(); // UNVERIFIED не является наблюдаемым риском
        assertThat(premium.summary().totalPotentialFine()).isEqualTo("от 150 000 до 500 000 ₽");
        assertThat(premium.quality().passed()).isEqualTo(1);
        assertThat(premium.quality().failed()).isEqualTo(1);
        assertThat(premium.quality().notEvaluated()).isEqualTo(1);
        assertThat(premium.quality().coveragePercent()).isEqualTo(67);
        assertThat(premium.quality().unverifiedRules()).singleElement().satisfies(rule -> {
            assertThat(rule.code()).isEqualTo("RKN_REGISTRY_NOT_VERIFIED");
            assertThat(rule.title()).isEqualTo("rkn");
        });
        assertThat(premium.quality().positiveChecks()).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("NO_PRIVACY_POLICY");
            assertThat(p.title()).isEqualTo("Политика обработки персональных данных найдена");
        });

        var tracker = premium.findings().get(0);
        assertThat(tracker.code()).isEqualTo("THIRD_PARTY_TRACKERS");
        assertThat(tracker.sourceUrl()).isEqualTo("https://site.ru/a");
        assertThat(tracker.evidence()).isEqualTo("evidence tracker-a");
        assertThat(tracker.matchedSignals()).containsExactly("tracker-a");
        assertThat(tracker.affectedPages()).hasSize(2);
        assertThat(tracker.affectedPages())
                .extracting(p -> p.url())
                .containsExactly("https://site.ru/a", "https://site.ru/b");
        assertThat(tracker.affectedPages().get(0).evidence()).isEqualTo("evidence tracker-a");
        assertThat(tracker.affectedPages().get(0).matchedSignals()).containsExactly("tracker-a");
    }

    @Test
    void doesNotExposeFineOrCountSeverityForUnverifiedFinding() throws Exception {
        ComplianceScan scan = scan();
        ScanReportSnapshots snapshots = builder.build(scan, List.of(
                finding("RKN_REGISTRY_NOT_VERIFIED", FindingSeverity.CRITICAL, 0.95,
                        "https://site.ru/", "lookup failed", VerificationStatus.UNVERIFIED)));

        ScanReportResponse premium = objectMapper.readValue(snapshots.premiumJson(), ScanReportResponse.class);

        assertThat(premium.summary().critical()).isZero();
        assertThat(premium.summary().totalPotentialFine()).isNull();
        assertThat(premium.findings()).singleElement().satisfies(f -> {
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
            assertThat(f.fineAmount()).isNull();
        });
    }

    @Test
    void movesUnverifiedFactFromFailedToCoverageGapAndExposesReason() throws Exception {
        ComplianceScan scan = scan();
        scan.setDiagnosticsJson("""
                {
                  "pagesAttempted": 1,
                  "pagesFetched": 1,
                  "pagesFailed": 0,
                  "crawlerTimedOut": false,
                  "ruleErrors": [],
                  "ruleOutcomes": [
                    {"code": "NO_PRIVACY_POLICY", "status": "FAILED", "title": "Не найдена политика",
                     "severity": "HIGH", "category": "DOCUMENTS"}
                  ]
                }
                """);

        ScanReportResponse premium = objectMapper.readValue(builder.build(scan, List.of(
                finding("NO_PRIVACY_POLICY", FindingSeverity.HIGH, 0.8,
                        "https://site.ru/", "privacy-policy-link-absent", VerificationStatus.UNVERIFIED)
        )).premiumJson(), ScanReportResponse.class);

        assertThat(premium.quality().passed()).isZero();
        assertThat(premium.quality().failed()).isZero();
        assertThat(premium.quality().notEvaluated()).isEqualTo(1);
        assertThat(premium.quality().coveragePercent()).isZero();
        assertThat(premium.quality().unverifiedRules()).singleElement().satisfies(rule -> {
            assertThat(rule.code()).isEqualTo("NO_PRIVACY_POLICY");
            assertThat(rule.reason()).isEqualTo("evidence privacy-policy-link-absent");
        });

        ScanReportResponse free = objectMapper.readValue(builder.build(scan, List.of(
                finding("NO_PRIVACY_POLICY", FindingSeverity.HIGH, 0.8,
                        "https://site.ru/", "privacy-policy-link-absent", VerificationStatus.UNVERIFIED)
        )).freeJson(), ScanReportResponse.class);
        assertThat(free.quality().coveragePercent()).isZero();
        assertThat(free.quality().unverifiedRules()).singleElement()
                .satisfies(rule -> assertThat(rule.reason()).isNull());
    }

    @Test
    void neverParsesArticleNumbersAsPotentialFine() throws Exception {
        ComplianceScan scan = scan();
        ComplianceFinding finding = finding("MISSING_HSTS", FindingSeverity.MEDIUM, 0.9,
                "https://site.ru/", "header absent", VerificationStatus.DETECTED);
        finding.setFineAmount("Без прямого штрафа: мера защиты по ст. 19 152-ФЗ");

        ScanReportResponse premium = objectMapper.readValue(
                builder.build(scan, List.of(finding)).premiumJson(), ScanReportResponse.class);

        assertThat(premium.summary().totalPotentialFine()).isNull();
    }

    @Test
    void freeShowsSanctionHeadlineWhilePremiumContainsScenarioDetails() throws Exception {
        ComplianceScan scan = scan();
        ComplianceFinding hosting = finding("HOSTING_OUTSIDE_RU_DETECTED", FindingSeverity.HIGH, 0.9,
                "https://site.ru/", "public host outside RU", VerificationStatus.DETECTED);

        ScanReportSnapshots snapshots = builder.build(scan, List.of(hosting));
        ScanReportResponse premium = objectMapper.readValue(snapshots.premiumJson(), ScanReportResponse.class);
        ScanReportResponse free = objectMapper.readValue(snapshots.freeJson(), ScanReportResponse.class);

        assertThat(premium.summary().totalPotentialFine()).isEqualTo("от 1 000 000 до 18 000 000 ₽");
        assertThat(free.summary().totalPotentialFine()).isEqualTo(premium.summary().totalPotentialFine());
        assertThat(premium.summary().sanctionExposure().headline())
                .isEqualTo("От 1 000 000 до 18 000 000 ₽ — суммарно по потенциальным нарушениям");
        assertThat(premium.summary().sanctionExposure().scenariosAreNotSummed()).isFalse();
        assertThat(premium.summary().sanctionExposure().scenarios()).hasSize(2);
        assertThat(free.summary().sanctionExposure().headline())
                .isEqualTo(premium.summary().sanctionExposure().headline());
        assertThat(free.summary().sanctionExposure().scenarios()).isEmpty();
    }

    @Test
    void allActiveRuPassedRulesHavePositiveChecks() throws Exception {
        ComplianceScan scan = scan();
        scan.setDiagnosticsJson("""
                {
                  "pagesAttempted": 1,
                  "pagesFetched": 1,
                  "pagesFailed": 0,
                  "crawlerTimedOut": false,
                  "ruleErrors": [],
                  "ruleOutcomes": [
                    {"code": "NO_PRIVACY_POLICY", "status": "PASSED", "title": "policy", "severity": "HIGH", "category": "DOCUMENTS", "positiveTitle": "Политика обработки персональных данных найдена"},
                    {"code": "UNPROTECTED_DATA_FORMS", "status": "PASSED", "title": "forms", "severity": "CRITICAL", "category": "FORMS", "positiveTitle": "Небезопасные формы с персональными данными не обнаружены"},
                    {"code": "CONSENT_DEFAULT_CHECKED", "status": "PASSED", "title": "consent", "severity": "HIGH", "category": "CONSENT", "positiveTitle": "Предотмеченное согласие не обнаружено"},
                    {"code": "NO_COOKIE_CONSENT", "status": "PASSED", "title": "cookies", "severity": "MEDIUM", "category": "COOKIES", "positiveTitle": "Механизм cookie-согласия обнаружен"},
                    {"code": "THIRD_PARTY_TRACKERS", "status": "PASSED", "title": "trackers", "severity": "MEDIUM", "category": "TRACKERS", "positiveTitle": "Нераскрытые сторонние трекеры не обнаружены"},
                    {"code": "POSSIBLE_CROSS_BORDER_TRANSFER", "status": "PASSED", "title": "cross", "severity": "HIGH", "category": "HOSTING", "positiveTitle": "Риск трансграничной передачи через внешние сервисы не обнаружен"},
                    {"code": "POSSIBLE_FOREIGN_AUTH_PROVIDER", "status": "PASSED", "title": "auth", "severity": "HIGH", "category": "FORMS", "positiveTitle": "Иностранные сервисы авторизации не обнаружены"},
                    {"code": "NO_OPERATOR_CONTACTS", "status": "PASSED", "title": "operator", "severity": "MEDIUM", "category": "DOCUMENTS", "positiveTitle": "Сведения об операторе найдены"},
                    {"code": "HOSTING_OUTSIDE_RU_DETECTED", "status": "PASSED", "title": "hosting", "severity": "HIGH", "category": "HOSTING", "positiveTitle": "Сервер сайта расположен в Российской Федерации"},
                    {"code": "POSSIBLE_TRACKERS_BEFORE_CONSENT", "status": "PASSED", "title": "pre-consent", "severity": "HIGH", "category": "TRACKERS", "positiveTitle": "Загрузка трекеров до согласия не подтверждена"}
                  ]
                }
                """);

        ScanReportSnapshots snapshots = builder.build(scan, List.of());
        ScanReportResponse premium = objectMapper.readValue(snapshots.premiumJson(), ScanReportResponse.class);

        assertThat(premium.quality().passed()).isEqualTo(10);
        assertThat(premium.quality().positiveChecks())
                .extracting(p -> p.code())
                .containsExactly(
                        "NO_PRIVACY_POLICY",
                        "UNPROTECTED_DATA_FORMS",
                        "CONSENT_DEFAULT_CHECKED",
                        "NO_COOKIE_CONSENT",
                        "THIRD_PARTY_TRACKERS",
                        "POSSIBLE_CROSS_BORDER_TRANSFER",
                        "POSSIBLE_FOREIGN_AUTH_PROVIDER",
                        "NO_OPERATOR_CONTACTS",
                        "HOSTING_OUTSIDE_RU_DETECTED",
                        "POSSIBLE_TRACKERS_BEFORE_CONSENT");
    }

    @Test
    void suppressesWeakCspPositiveWhenCspMissing() throws Exception {
        // WEAK_CSP PASSED («CSP robust») бессмысленно, если MISSING_CSP FAILED (CSP нет вообще):
        // подавляется — не в positiveChecks и не в passed; учитывается как notEvaluated.
        ComplianceScan scan = scan();
        scan.setDiagnosticsJson("""
                {
                  "pagesAttempted": 1, "pagesFetched": 1, "pagesFailed": 0, "crawlerTimedOut": false,
                  "ruleErrors": [],
                  "ruleOutcomes": [
                    {"code": "MISSING_CSP", "status": "FAILED", "title": "no csp", "severity": "MEDIUM", "category": "SECURITY"},
                    {"code": "WEAK_CSP", "status": "PASSED", "title": "weak", "severity": "LOW", "category": "SECURITY",
                     "positiveTitle": "Content-Security-Policy is robust", "positiveMessage": "No weak directives."},
                    {"code": "MISSING_HSTS", "status": "PASSED", "title": "hsts", "severity": "MEDIUM", "category": "SECURITY",
                     "positiveTitle": "HSTS configured", "positiveMessage": "HSTS present."}
                  ]
                }
                """);

        ScanReportSnapshots snapshots = builder.build(scan, List.of(
                finding("MISSING_CSP", FindingSeverity.MEDIUM, 0.95,
                        "https://site.ru/", "no-csp", VerificationStatus.DETECTED)));
        ScanReportResponse premium = objectMapper.readValue(snapshots.premiumJson(), ScanReportResponse.class);

        // WEAK_CSP подавлено: только MISSING_HSTS зелёный; WEAK_CSP ушёл в notEvaluated.
        assertThat(premium.quality().passed()).isEqualTo(1);
        assertThat(premium.quality().failed()).isEqualTo(1);
        assertThat(premium.quality().notEvaluated()).isEqualTo(1);
        assertThat(premium.quality().positiveChecks())
                .extracting(p -> p.code())
                .containsExactly("MISSING_HSTS")
                .doesNotContain("WEAK_CSP");
    }

    @Test
    void keepsWeakCspPositiveWhenCspPresent() throws Exception {
        // MISSING_CSP PASSED → предпосылка не провалена → WEAK_CSP positive остаётся.
        ComplianceScan scan = scan();
        scan.setDiagnosticsJson("""
                {
                  "pagesAttempted": 1, "pagesFetched": 1, "pagesFailed": 0, "crawlerTimedOut": false,
                  "ruleErrors": [],
                  "ruleOutcomes": [
                    {"code": "MISSING_CSP", "status": "PASSED", "title": "csp", "severity": "MEDIUM", "category": "SECURITY",
                     "positiveTitle": "CSP present", "positiveMessage": "CSP header present."},
                    {"code": "WEAK_CSP", "status": "PASSED", "title": "weak", "severity": "LOW", "category": "SECURITY",
                     "positiveTitle": "Content-Security-Policy is robust", "positiveMessage": "No weak directives."}
                  ]
                }
                """);

        ScanReportSnapshots snapshots = builder.build(scan, List.of());
        ScanReportResponse premium = objectMapper.readValue(snapshots.premiumJson(), ScanReportResponse.class);

        assertThat(premium.quality().passed()).isEqualTo(2);
        assertThat(premium.quality().positiveChecks())
                .extracting(p -> p.code())
                .containsExactlyInAnyOrder("MISSING_CSP", "WEAK_CSP");
    }

    private ComplianceScan scan() {
        ComplianceScan scan = new ComplianceScan();
        scan.setId(UUID.randomUUID());
        scan.setStatus(ScanStatus.COMPLETED);
        scan.setSiteUrl("https://example.com");
        scan.setSiteDomain("example.com");
        scan.setScore(80);
        scan.setTier(ScanTier.FREE);
        scan.setKind(ScanKind.CABINET_PREMIUM);
        scan.setJurisdiction(ScanJurisdiction.RU);
        scan.setCreatedAt(Instant.now());
        scan.setFinishedAt(Instant.now());
        return scan;
    }

    private ComplianceFinding finding(UUID scanId) {
        ComplianceFinding f = new ComplianceFinding();
        f.setId(UUID.randomUUID());
        f.setScanId(scanId);
        f.setCode("NO_COOKIE_CONSENT");
        f.setSeverity(FindingSeverity.CRITICAL);
        f.setCategory(FindingCategory.CONSENT);
        f.setTitle("No cookie consent");
        f.setFineAmount("от 60 000 до 100 000 руб");
        f.setExplanation("because reasons");
        f.setRecommendation("add banner");
        f.setEvidence("found cookie");
        f.setSourceUrl("https://example.com/page");
        f.setPageUrl("https://example.com/page");
        f.setSourceType(SourceType.HTML);
        f.setConfidence(0.9);
        f.setVerificationStatus(VerificationStatus.CONFIRMED);
        f.setMatchedSignals("ym_uid,_ga");
        return f;
    }

    private ComplianceFinding finding(String code, FindingSeverity severity, Double confidence,
                                      String sourceUrl, String signal,
                                      VerificationStatus verificationStatus) {
        ComplianceFinding finding = new ComplianceFinding();
        finding.setId(UUID.randomUUID());
        finding.setCode(code);
        finding.setSeverity(severity);
        finding.setCategory(FindingCategory.TRACKERS);
        finding.setTitle(code);
        finding.setFineAmount(fineAmount(code));
        finding.setLegalBasis("law");
        finding.setExplanation("explanation");
        finding.setRecommendation("recommendation");
        finding.setEvidence("evidence " + signal);
        finding.setSourceUrl(sourceUrl);
        finding.setPageUrl(sourceUrl);
        finding.setSourceType(SourceType.HTML);
        finding.setConfidence(confidence);
        finding.setVerificationStatus(verificationStatus);
        finding.setEvidenceType(EvidenceType.DYNAMIC_RENDER);
        finding.setMatchedSignals(signal);
        return finding;
    }

    private static String fineAmount(String code) {
        return switch (code) {
            case "THIRD_PARTY_TRACKERS" -> "150 000 – 300 000 ₽; повторно 300 000 – 500 000 ₽";
            case "RKN_REGISTRY_NOT_VERIFIED" -> "100 000 – 300 000 ₽";
            default -> "10 000 ₽";
        };
    }
}
