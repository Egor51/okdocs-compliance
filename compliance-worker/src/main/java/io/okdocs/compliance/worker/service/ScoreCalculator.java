package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Расчёт score сайта (§5.5): {@code 100 − Σ(взвешенные очки)}. Перенос проверенной логики из MVP
 * okdocks; типы — контрактные enum'ы вместо строк.
 * <p>
 * Дедупликация по {@code code}: per-page findings одного правила считаются один раз
 * (репрезентативный — с наибольшей confidence, худший случай).
 */
@Component
public class ScoreCalculator {

    // Базовые очки за severity (§5.5). Подобраны так, чтобы три серьёзных CONFIRMED-нарушения
    // (HIGH + 2×MEDIUM) давали score ≈ 48, а не 62 — пользователь видит реальный риск.
    private static final int BASE_CRITICAL = 30;
    private static final int BASE_HIGH = 20;
    private static final int BASE_MEDIUM = 12;
    private static final int BASE_LOW = 5;

    public int calculate(List<ComplianceFinding> findings) {
        int score = 100;
        for (ComplianceFinding f : deduplicate(findings)) {
            score -= weighted(f);
        }
        return Math.max(score, 0);
    }

    /** Returns one finding per rule code, picking the highest-confidence instance. */
    public List<ComplianceFinding> deduplicate(List<ComplianceFinding> findings) {
        return findings.stream()
                .collect(Collectors.toMap(
                        ComplianceFinding::getCode,
                        f -> f,
                        (a, b) -> (b.getConfidence() != null && (a.getConfidence() == null
                                || b.getConfidence() > a.getConfidence())) ? b : a))
                .values().stream().toList();
    }

    private static int weighted(ComplianceFinding f) {
        int base = basePoints(f.getSeverity());
        // CONFIRMED — подтверждено → 100%; DETECTED — технический сигнал, юр-контекст не проверен → 65%;
        // UNVERIFIED + null — паттерн-инференс, требует ручной проверки → 80%.
        double weight = f.getVerificationStatus() == VerificationStatus.CONFIRMED ? 1.00
                : f.getVerificationStatus() == VerificationStatus.DETECTED ? 0.65
                : 0.80;
        return (int) Math.round(base * weight);
    }

    private static int basePoints(FindingSeverity severity) {
        if (severity == null) {
            return 0;
        }
        return switch (severity) {
            case CRITICAL -> BASE_CRITICAL;
            case HIGH -> BASE_HIGH;
            case MEDIUM -> BASE_MEDIUM;
            case LOW -> BASE_LOW;
        };
    }
}
