package io.okdocs.compliance.contracts.crawler;

import io.okdocs.compliance.contracts.enums.RegistryStatus;

import java.util.List;

/**
 * Единый вход движка правил. Worker наполняет контекст (включая enrichment) до запуска RuleEngine;
 * правила остаются чистыми функциями {@code ctx → facts}.
 * <p>
 * Enrichment-поля nullable: внешние источники могут быть недоступны. Для реестра «недоступен»
 * выражается явным {@link RegistryStatus#LOOKUP_FAILED}, для GeoIP — {@code hostCountry == null}.
 * Правила при отсутствии данных возвращают факт с {@code UNVERIFIED}, а не молчат.
 */
public record ScanAnalysisContext(
        List<PageAnalysisResult> pages,
        String hostCountry,
        List<String> resolvedIps,
        RegistryStatus registryStatus,
        CrawlerDiagnostics diagnostics
) {
}
