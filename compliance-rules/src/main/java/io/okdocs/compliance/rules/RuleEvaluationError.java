package io.okdocs.compliance.rules;

/**
 * Ошибка одного правила, изолированная движком (не валит весь анализ).
 * <p>
 * {@code exceptionType} (simple class name, напр. {@code NullPointerException}) отделяет
 * неожиданный баг от ожидаемой ошибки парсинга в {@code DiagnosticsDto.ruleErrors} — облегчает
 * диагностику. Диагностика анализа, отдельная от метрик краулера.
 */
public record RuleEvaluationError(
        String ruleCode,
        String exceptionType,
        String message
) {
}
