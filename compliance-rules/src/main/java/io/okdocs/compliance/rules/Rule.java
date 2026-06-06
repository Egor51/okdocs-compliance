package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;

import java.util.List;

/**
 * Правило проверки соответствия. Чистая функция {@code ctx → facts}: получает наполненный
 * worker'ом {@link ScanAnalysisContext} (включая enrichment-данные) и возвращает наблюдения.
 * Без Spring/JPA — правила тестируются без контекста.
 */
public interface Rule {

    RuleDefinition definition();

    List<RuleFact> evaluate(ScanAnalysisContext ctx);
}
