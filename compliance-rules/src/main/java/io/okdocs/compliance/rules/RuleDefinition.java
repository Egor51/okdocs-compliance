package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;

/**
 * Дефолтные метаданные правила (значения по умолчанию). В рантайме часть полей может быть
 * переопределена из БД через {@code rule_config} (⏸ DEFERRED): слияние «дефолт + override»
 * происходит в worker при сборке findings, НЕ в правиле — {@code compliance-rules} остаётся
 * чистым, без БД/Spring. Логика детекции ({@link Rule#evaluate}) в БД НЕ хранится — только эти
 * метаданные.
 */
public record RuleDefinition(
        String code,
        FindingSeverity severity,
        FindingCategory category,
        String title,
        String fineAmount,
        String legalBasis,
        String explanation,
        String recommendation
) {
}
