package io.okdocs.compliance.rules;

import java.util.List;

/**
 * Результат прогона движка: собранные факты + изолированные ошибки правил. Ошибку отдельного
 * правила движок не роняет наружу, а собирает в {@code errors} (один битый rule не валит анализ).
 */
public record RuleEngineResult(
        List<RuleFact> facts,
        List<RuleEvaluationError> errors
) {
}
