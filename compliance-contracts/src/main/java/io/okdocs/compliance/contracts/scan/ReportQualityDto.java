package io.okdocs.compliance.contracts.scan;

import java.util.List;

/**
 * Сводка качества отчёта: сколько правил пройдено, провалено и не проверено.
 * {@code coveragePercent} показывает долю правил с однозначным автоматическим результатом;
 * {@code unverifiedRules} объясняет, почему остальные правила требуют ручной проверки.
 */
public record ReportQualityDto(
        int passed,
        int failed,
        int notEvaluated,
        List<PositiveCheckDto> positiveChecks,
        Integer coveragePercent,
        List<UnverifiedRuleDto> unverifiedRules
) {
    public ReportQualityDto {
        positiveChecks = positiveChecks == null ? List.of() : List.copyOf(positiveChecks);
        unverifiedRules = unverifiedRules == null ? List.of() : List.copyOf(unverifiedRules);
    }

    /** Совместимость с внутренними вызовами и тестами, созданными до coverage-контракта. */
    public ReportQualityDto(int passed, int failed, int notEvaluated, List<PositiveCheckDto> positiveChecks) {
        this(passed, failed, notEvaluated, positiveChecks,
                calculateCoveragePercent(passed, failed, notEvaluated), List.of());
    }

    private static Integer calculateCoveragePercent(int passed, int failed, int notEvaluated) {
        int total = passed + failed + notEvaluated;
        return total == 0 ? null : (int) Math.round((passed + failed) * 100.0 / total);
    }
}
