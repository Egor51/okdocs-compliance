package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreCalculatorTest {

    // Дефолтные properties = эталонная score-модель (core-yml совпадает с Java-дефолтами).
    private final ScoreCalculator calculator = new ScoreCalculator(new ComplianceWorkerProperties());

    private static ComplianceFinding finding(String code, FindingSeverity severity,
                                             VerificationStatus verification, Double confidence) {
        ComplianceFinding f = new ComplianceFinding();
        f.setCode(code);
        f.setSeverity(severity);
        f.setVerificationStatus(verification);
        f.setConfidence(confidence);
        return f;
    }

    @Test
    void emptyFindingsGivePerfectScore() {
        assertThat(calculator.calculate(List.of())).isEqualTo(100);
    }

    @Test
    void confirmedAppliesFullWeight() {
        // CRITICAL=30 @ 100% → 100-30 = 70
        var findings = List.of(finding("A", FindingSeverity.CRITICAL, VerificationStatus.CONFIRMED, 1.0));
        assertThat(calculator.calculate(findings)).isEqualTo(70);
    }

    @Test
    void detectedAppliesSixtyFivePercent() {
        // HIGH=20 @ 65% = 13 → 100-13 = 87
        var findings = List.of(finding("A", FindingSeverity.HIGH, VerificationStatus.DETECTED, 0.9));
        assertThat(calculator.calculate(findings)).isEqualTo(87);
    }

    @Test
    void unverifiedAndNullDoNotReduceRiskScore() {
        assertThat(calculator.calculate(List.of(
                finding("A", FindingSeverity.MEDIUM, VerificationStatus.UNVERIFIED, 0.7)))).isEqualTo(100);
        assertThat(calculator.calculate(List.of(
                finding("B", FindingSeverity.MEDIUM, null, null)))).isEqualTo(100);
    }

    @Test
    void falsePositiveDoesNotReduceRiskScore() {
        assertThat(calculator.calculate(List.of(
                finding("A", FindingSeverity.CRITICAL, VerificationStatus.FALSE_POSITIVE, 1.0)))).isEqualTo(100);
    }

    @Test
    void scoreNeverGoesNegative() {
        // 4×CRITICAL CONFIRMED = 4×30 = 120 > 100 → clamp 0
        var findings = List.of(
                finding("A", FindingSeverity.CRITICAL, VerificationStatus.CONFIRMED, 1.0),
                finding("B", FindingSeverity.CRITICAL, VerificationStatus.CONFIRMED, 1.0),
                finding("C", FindingSeverity.CRITICAL, VerificationStatus.CONFIRMED, 1.0),
                finding("D", FindingSeverity.CRITICAL, VerificationStatus.CONFIRMED, 1.0));
        assertThat(calculator.calculate(findings)).isZero();
    }

    @Test
    void deduplicatesByCodeKeepingHighestConfidence() {
        // Два finding одного кода: дедуп оставляет один (с большей confidence).
        var findings = List.of(
                finding("DUP", FindingSeverity.HIGH, VerificationStatus.DETECTED, 0.5),
                finding("DUP", FindingSeverity.HIGH, VerificationStatus.DETECTED, 0.9));
        assertThat(calculator.deduplicate(findings)).hasSize(1);
        assertThat(calculator.deduplicate(findings).get(0).getConfidence()).isEqualTo(0.9);
    }

    @Test
    void deduplicationPrefersDetectedOverHigherConfidenceUnverified() {
        var findings = List.of(
                finding("DUP", FindingSeverity.HIGH, VerificationStatus.DETECTED, 0.7),
                finding("DUP", FindingSeverity.HIGH, VerificationStatus.UNVERIFIED, 0.95));

        assertThat(calculator.deduplicate(findings)).singleElement().satisfies(f -> {
            assertThat(f.getVerificationStatus()).isEqualTo(VerificationStatus.DETECTED);
            assertThat(f.getConfidence()).isEqualTo(0.7);
        });
        assertThat(calculator.calculate(findings)).isEqualTo(87);
    }

    @Test
    void scoreCountsEachCodeOnce() {
        // 3 finding кода DUP не должны вычитаться трижды — только один раз (HIGH CONFIRMED=20).
        var findings = List.of(
                finding("DUP", FindingSeverity.HIGH, VerificationStatus.CONFIRMED, 1.0),
                finding("DUP", FindingSeverity.HIGH, VerificationStatus.CONFIRMED, 1.0),
                finding("DUP", FindingSeverity.HIGH, VerificationStatus.CONFIRMED, 1.0));
        assertThat(calculator.calculate(findings)).isEqualTo(80);
    }

    @Test
    void mixedSeveritiesSummed() {
        // HIGH CONFIRMED (20) + 2×MEDIUM CONFIRMED (12+12) = 44 → 100-44 = 56
        var findings = List.of(
                finding("A", FindingSeverity.HIGH, VerificationStatus.CONFIRMED, 1.0),
                finding("B", FindingSeverity.MEDIUM, VerificationStatus.CONFIRMED, 1.0),
                finding("C", FindingSeverity.MEDIUM, VerificationStatus.CONFIRMED, 1.0));
        assertThat(calculator.calculate(findings)).isEqualTo(56);
    }

    private static ComplianceFinding finding(String code, FindingSeverity severity,
                                             VerificationStatus verification, Double confidence,
                                             io.okdocs.compliance.contracts.enums.FindingCategory category) {
        ComplianceFinding f = finding(code, severity, verification, confidence);
        f.setCategory(category);
        return f;
    }

    @Test
    void securityCategoryDeductionIsCappedAt20() {
        // 9 SECURITY findings: 5×MEDIUM(12) + 4×LOW(5) @ DETECTED 0.65 = 5×8 + 4×3 = 52 без cap.
        // Cap SECURITY=20 ограничивает вклад → 100-20 = 80.
        var c = io.okdocs.compliance.contracts.enums.FindingCategory.SECURITY;
        var findings = List.of(
                finding("S1", FindingSeverity.MEDIUM, VerificationStatus.DETECTED, 0.9, c),
                finding("S2", FindingSeverity.MEDIUM, VerificationStatus.DETECTED, 0.9, c),
                finding("S3", FindingSeverity.MEDIUM, VerificationStatus.DETECTED, 0.9, c),
                finding("S4", FindingSeverity.MEDIUM, VerificationStatus.DETECTED, 0.9, c),
                finding("S5", FindingSeverity.MEDIUM, VerificationStatus.DETECTED, 0.9, c),
                finding("S6", FindingSeverity.LOW, VerificationStatus.DETECTED, 0.9, c),
                finding("S7", FindingSeverity.LOW, VerificationStatus.DETECTED, 0.9, c),
                finding("S8", FindingSeverity.LOW, VerificationStatus.DETECTED, 0.9, c),
                finding("S9", FindingSeverity.LOW, VerificationStatus.DETECTED, 0.9, c));
        assertThat(calculator.calculate(findings)).isEqualTo(80);
    }

    @Test
    void capDoesNotAffectCoreCategories_securityCappedSeparately() {
        // Core 152-ФЗ (DOCUMENTS, без cap) HIGH CONFIRMED=20 + пачка SECURITY (capped 20).
        var sec = io.okdocs.compliance.contracts.enums.FindingCategory.SECURITY;
        var doc = io.okdocs.compliance.contracts.enums.FindingCategory.DOCUMENTS;
        var findings = List.of(
                finding("CORE", FindingSeverity.HIGH, VerificationStatus.CONFIRMED, 1.0, doc),
                finding("S1", FindingSeverity.MEDIUM, VerificationStatus.DETECTED, 0.9, sec),
                finding("S2", FindingSeverity.MEDIUM, VerificationStatus.DETECTED, 0.9, sec),
                finding("S3", FindingSeverity.MEDIUM, VerificationStatus.DETECTED, 0.9, sec),
                finding("S4", FindingSeverity.MEDIUM, VerificationStatus.DETECTED, 0.9, sec));
        // DOCUMENTS: 20 (не capped). SECURITY: 4×8=32 → cap 20. Σ=40 → 100-40 = 60.
        assertThat(calculator.calculate(findings)).isEqualTo(60);
    }
}
