package io.okdocs.compliance.contracts.crawler;

import io.okdocs.compliance.contracts.enums.RenderMode;

import java.util.List;

/** Результат обработки одной страницы краулером. Вход для правил. */
public record PageAnalysisResult(
        String url,
        String title,
        String text,
        String html,
        List<String> externalScriptDomains,
        List<String> externalStyleDomains,
        List<String> internalLinks,
        boolean cookiePresent,
        List<FormInfo> forms,
        RenderMode renderMode,
        /**
         * Сторонние хосты, чьи сетевые запросы наблюдались ДО появления cookie-баннера (или при
         * полном отсутствии баннера) во время DYNAMIC-рендера через CDP. Прямое доказательство
         * «трекер сработал до согласия» (PLAN.md §3.2). На STATIC всегда пусто — Jsoup не исполняет
         * JS и порядок загрузки не наблюдается. Хосты сырые (без матчинга на справочник трекеров) —
         * фильтрацию «является ли трекером» делает правило, чтобы контракт остался jurisdiction-neutral.
         */
        List<String> preConsentTrackerHosts
) {
    public PageAnalysisResult {
        preConsentTrackerHosts = preConsentTrackerHosts == null
                ? List.of()
                : List.copyOf(preConsentTrackerHosts);
    }

    /**
     * STATIC-совместимый конструктор без наблюдения порядка загрузки: {@code preConsentTrackerHosts}
     * пуст. Используется Jsoup-краулером и тестами, которым timeline недоступен/не нужен.
     */
    public PageAnalysisResult(String url, String title, String text, String html,
                              List<String> externalScriptDomains, List<String> externalStyleDomains,
                              List<String> internalLinks, boolean cookiePresent,
                              List<FormInfo> forms, RenderMode renderMode) {
        this(url, title, text, html, externalScriptDomains, externalStyleDomains, internalLinks,
                cookiePresent, forms, renderMode, List.of());
    }
}
