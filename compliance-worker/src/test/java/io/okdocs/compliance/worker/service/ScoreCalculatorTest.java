package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreCalculatorTest {

    private final ScoreCalculator calculator = new ScoreCalculator();

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
    void unverifiedAndNullApplyEightyPercent() {
        // MEDIUM=12 @ 80% = 9.6 → round 10 → 100-10 = 90
        assertThat(calculator.calculate(List.of(
                finding("A", FindingSeverity.MEDIUM, VerificationStatus.UNVERIFIED, 0.7)))).isEqualTo(90);
        // null verification трактуется как UNVERIFIED (80%)
        assertThat(calculator.calculate(List.of(
                finding("B", FindingSeverity.MEDIUM, null, null)))).isEqualTo(90);
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
}
