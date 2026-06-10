package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Расчёт score сайта (§5.5): {@code initial − Σ(взвешенные очки)}. Перенос проверенной логики из MVP
 * okdocks; типы — контрактные enum'ы вместо строк.
 * <p>
 * Модель (базовые очки по severity, веса по {@link VerificationStatus}, стартовый балл) вынесена в
 * {@code compliance.score.*} ({@link ComplianceWorkerProperties.Score}) — тюнится без пересборки и
 * едина для worker и app. Дефолты подобраны так, чтобы три серьёзных CONFIRMED-нарушения
 * (HIGH + 2×MEDIUM) давали score ≈ 48, а не 62 — пользователь видит реальный риск.
 * <p>
 * Дедупликация по {@code code}: per-page findings одного правила считаются один раз
 * (репрезентативный — с наибольшей confidence, худший случай).
 */
@Component
public class ScoreCalculator {

    private final ComplianceWorkerProperties.Score config;

    public ScoreCalculator(ComplianceWorkerProperties properties) {
        this.config = properties.getScore();
    }

    public int calculate(List<ComplianceFinding> findings) {
        int score = config.getInitial();
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

    private int weighted(ComplianceFinding f) {
        int base = config.basePointsFor(f.getSeverity());
        // CONFIRMED → 100%; DETECTED — техсигнал без юр-контекста → 65%; UNVERIFIED/null — паттерн-
        // инференс → DEFAULT (80%). Ключ — имя статуса; null статус берёт DEFAULT.
        VerificationStatus status = f.getVerificationStatus();
        double weight = config.weightFor(status == null ? null : status.name());
        return (int) Math.round(base * weight);
    }
}
