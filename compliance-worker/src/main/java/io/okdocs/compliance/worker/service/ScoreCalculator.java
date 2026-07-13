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
 * Дедупликация по {@code code}: per-page findings одного правила считаются один раз. Сначала
 * выбирается наиболее доказательный verification status, затем confidence. Поэтому вероятностный
 * {@code UNVERIFIED} с высокой confidence не может вытеснить наблюдаемый {@code DETECTED}.
 * {@code UNVERIFIED}, {@code FALSE_POSITIVE} и null не уменьшают score: это неполнота проверки или
 * опровергнутый риск, а не нарушение.
 */
@Component
public class ScoreCalculator {

    private final ComplianceWorkerProperties.Score config;

    public ScoreCalculator(ComplianceWorkerProperties properties) {
        this.config = properties.getScore();
    }

    public int calculate(List<ComplianceFinding> findings) {
        // Считаем вычет по категориям: технические категории (SECURITY/COOKIES) дают пачку findings,
        // чей совокупный вклад ограничивается capFor(category), чтобы они не перевешивали core
        // 152-ФЗ нарушения. Категории без cap вычитаются полностью.
        // HashMap, а не EnumMap: finding может иметь category == null (наследие/тесты), EnumMap бросает
        // NPE на null-ключе. null-категория не имеет cap → вычитается полностью.
        java.util.Map<io.okdocs.compliance.contracts.enums.FindingCategory, Integer> byCategory =
                new java.util.HashMap<>();
        for (ComplianceFinding f : deduplicate(findings)) {
            byCategory.merge(f.getCategory(), weighted(f), Integer::sum);
        }

        int totalDeduction = 0;
        for (var entry : byCategory.entrySet()) {
            Integer cap = config.capFor(entry.getKey());
            int deduction = cap == null ? entry.getValue() : Math.min(entry.getValue(), cap);
            totalDeduction += deduction;
        }
        return Math.max(config.getInitial() - totalDeduction, 0);
    }

    /** Returns one finding per rule code, preferring verified observations, then confidence. */
    public List<ComplianceFinding> deduplicate(List<ComplianceFinding> findings) {
        return findings.stream()
                .collect(Collectors.toMap(
                        ComplianceFinding::getCode,
                        f -> f,
                        ScoreCalculator::betterRiskRepresentative))
                .values().stream().toList();
    }

    private static ComplianceFinding betterRiskRepresentative(ComplianceFinding a, ComplianceFinding b) {
        int statusComparison = Integer.compare(riskRank(b.getVerificationStatus()), riskRank(a.getVerificationStatus()));
        if (statusComparison != 0) {
            return statusComparison > 0 ? b : a;
        }
        double aConfidence = a.getConfidence() == null ? -1.0 : a.getConfidence();
        double bConfidence = b.getConfidence() == null ? -1.0 : b.getConfidence();
        return bConfidence > aConfidence ? b : a;
    }

    private static int riskRank(VerificationStatus status) {
        if (status == VerificationStatus.CONFIRMED) {
            return 2;
        }
        if (status == VerificationStatus.DETECTED) {
            return 1;
        }
        return 0;
    }

    private int weighted(ComplianceFinding f) {
        int base = config.basePointsFor(f.getSeverity());
        // CONFIRMED → 100%; DETECTED — наблюдаемый техсигнал без полного юр-контекста → 65%;
        // UNVERIFIED/FALSE_POSITIVE/null → 0%. Ключ — имя статуса; неизвестный/null берёт DEFAULT.
        VerificationStatus status = f.getVerificationStatus();
        double weight = config.weightFor(status == null ? null : status.name());
        return (int) Math.round(base * weight);
    }
}
