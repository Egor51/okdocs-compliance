package io.okdocs.compliance.contracts.crawler;

import io.okdocs.compliance.contracts.enums.RegistryStatus;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;

import java.util.List;

/**
 * Единый вход движка правил. Worker наполняет контекст (включая enrichment) до запуска RuleEngine;
 * правила остаются чистыми функциями {@code ctx → facts}.
 * <p>
 * {@code jurisdiction} — «по какому закону проверяем» (152-ФЗ / GDPR), приходит явно с фронта и
 * хранится в строке скана. Это <b>не</b> {@code hostCountry}: страна хостинга отвечает на вопрос
 * «где сервер» (RU-сайт может хоститься в DE, .ru не гарантия), а юрисдикцию выбирает заказчик.
 * {@link RuleEngine} по {@code jurisdiction} решает, какие правила запускать.
 * <p>
 * Enrichment-поля nullable: внешние источники могут быть недоступны. Для реестра «недоступен»
 * выражается явным {@link RegistryStatus#LOOKUP_FAILED}, для GeoIP — {@code hostCountry == null}.
 * Правила при отсутствии данных возвращают факт с {@code UNVERIFIED}, а не молчат.
 */
public record ScanAnalysisContext(
        ScanJurisdiction jurisdiction,
        List<PageAnalysisResult> pages,
        String hostCountry,
        List<String> resolvedIps,
        RegistryStatus registryStatus,
        CrawlerDiagnostics diagnostics
) {
}
