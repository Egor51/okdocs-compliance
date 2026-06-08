package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;

import java.util.Objects;

/**
 * Дефолтные метаданные правила (значения по умолчанию). В рантайме часть полей может быть
 * переопределена из БД через {@code rule_config} (⏸ DEFERRED): слияние «дефолт + override»
 * происходит в worker при сборке findings, НЕ в правиле — {@code compliance-rules} остаётся
 * чистым, без БД/Spring. Логика детекции ({@link Rule#evaluate}) в БД НЕ хранится — только эти
 * метаданные.
 * <p>
 * {@code jurisdiction} — закон, к которому относится правило (152-ФЗ → {@code RU}, GDPR → {@code EU}).
 * {@link RuleEngine} запускает только правила, чья юрисдикция совпадает с
 * {@link io.okdocs.compliance.contracts.crawler.ScanAnalysisContext#jurisdiction()} скана. Это
 * именно «по какому закону проверяем», а не страна хостинга ({@code hostCountry}).
 */
public record RuleDefinition(
        String code,
        ScanJurisdiction jurisdiction,
        FindingSeverity severity,
        FindingCategory category,
        String title,
        String fineAmount,
        String legalBasis,
        String explanation,
        String recommendation
) {

    public RuleDefinition {
        Objects.requireNonNull(jurisdiction, "jurisdiction");
    }
}
