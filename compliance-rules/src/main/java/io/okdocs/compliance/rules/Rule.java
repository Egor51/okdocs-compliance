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

    /**
     * Применимо ли правило к данному контексту — есть ли у него входные данные для проверки.
     * По умолчанию {@code true} (правилу всегда есть что анализировать на странице).
     * <p>
     * Правила, зависящие от данных, которые могут отсутствовать (cookies/storage — только при
     * DYNAMIC-рендере; TLS-снимок — может не сняться), переопределяют метод: если данных нет, правило
     * НЕ должно давать «проверка пройдена» (PASSED) — это вводит в заблуждение («не нашли нарушений»
     * там, где просто не проверяли). {@link RuleEngine} при {@code appliesTo == false} помечает
     * правило {@code NOT_EVALUATED}, а не PASSED/FAILED.
     */
    default boolean appliesTo(ScanAnalysisContext ctx) {
        return true;
    }
}
