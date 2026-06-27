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
        List<String> preConsentTrackerHosts,
        /**
         * Cookies, наблюдённые в браузере ДО взаимодействия с cookie-баннером во время DYNAMIC-рендера
         * (Этап 4, Phase 1). Снимок состояния до согласия: текущий проход не кликает по баннеру, поэтому
         * это и есть «cookies до согласия». На STATIC всегда пусто (нет CDP). Вход для cookie-правил.
         */
        List<ObservedCookie> preConsentCookies,
        /**
         * Ключи Web Storage (localStorage), наблюдённые ДО согласия во время DYNAMIC-рендера. Сырые
         * имена ключей (без матчинга на трекеры) — классификацию делает правило. На STATIC пусто.
         */
        List<String> preConsentStorageKeys,
        /**
         * Был ли успешно снят CDP-снимок cookies до согласия. true + пустой список означает, что
         * cookies реально не наблюдались; false означает, что проверка не проводилась/не удалась.
         */
        boolean preConsentCookiesSnapshotAvailable,
        /**
         * Был ли успешно снят JS-снимок localStorage до согласия. true + пустой список означает, что
         * ключей реально нет; false означает, что проверка не проводилась/не удалась.
         */
        boolean preConsentStorageSnapshotAvailable,
        /**
         * Результат прогона consent-сценариев (Reject/Accept) на странице (Фаза 4). {@code null} на
         * STATIC и на DYNAMIC-сканах без consent-взаимодействия; {@code available == false} внутри —
         * сценарий не отработал (нет баннера/сбой). Вход для EU/UK consent-правил.
         */
        ConsentScenarioResult consentScenario
) {
    public PageAnalysisResult {
        preConsentTrackerHosts = preConsentTrackerHosts == null
                ? List.of()
                : List.copyOf(preConsentTrackerHosts);
        preConsentCookies = preConsentCookies == null ? List.of() : List.copyOf(preConsentCookies);
        preConsentStorageKeys = preConsentStorageKeys == null ? List.of() : List.copyOf(preConsentStorageKeys);
    }

    /**
     * STATIC-совместимый конструктор без наблюдения порядка загрузки: pre-consent поля пусты.
     * Используется Jsoup-краулером и тестами, которым timeline недоступен/не нужен.
     */
    public PageAnalysisResult(String url, String title, String text, String html,
                              List<String> externalScriptDomains, List<String> externalStyleDomains,
                              List<String> internalLinks, boolean cookiePresent,
                              List<FormInfo> forms, RenderMode renderMode) {
        this(url, title, text, html, externalScriptDomains, externalStyleDomains, internalLinks,
                cookiePresent, forms, renderMode, List.of(), List.of(), List.of(), false, false, null);
    }

    /**
     * DYNAMIC-конструктор только с pre-consent сетевыми хостами (без cookies/storage). Сохранён
     * совместимым для существующих вызовов CDP-краулера и тестов timeline трекеров.
     */
    public PageAnalysisResult(String url, String title, String text, String html,
                              List<String> externalScriptDomains, List<String> externalStyleDomains,
                              List<String> internalLinks, boolean cookiePresent,
                              List<FormInfo> forms, RenderMode renderMode,
                              List<String> preConsentTrackerHosts) {
        this(url, title, text, html, externalScriptDomains, externalStyleDomains, internalLinks,
                cookiePresent, forms, renderMode, preConsentTrackerHosts, List.of(), List.of(), false,
                false, null);
    }

    /**
     * Совместимый конструктор с cookies/storage, но без явных флагов доступности snapshots. Флаги
     * намеренно остаются {@code false}: наличие списков не доказывает, что CDP/JS snapshot реально
     * был снят. DYNAMIC-код, которому нужен PASS/NOT_EVALUATED distinction, должен вызывать полную
     * перегрузку с явными флагами.
     */
    public PageAnalysisResult(String url, String title, String text, String html,
                              List<String> externalScriptDomains, List<String> externalStyleDomains,
                              List<String> internalLinks, boolean cookiePresent,
                              List<FormInfo> forms, RenderMode renderMode,
                              List<String> preConsentTrackerHosts,
                              List<ObservedCookie> preConsentCookies,
                              List<String> preConsentStorageKeys) {
        this(url, title, text, html, externalScriptDomains, externalStyleDomains, internalLinks,
                cookiePresent, forms, renderMode, preConsentTrackerHosts, preConsentCookies,
                preConsentStorageKeys, false, false, null);
    }

    /**
     * DYNAMIC-конструктор с pre-consent данными + флагами + consent-сценарием. Полная DYNAMIC-форма
     * для CDP-краулера, прогоняющего Reject/Accept.
     */
    public PageAnalysisResult(String url, String title, String text, String html,
                              List<String> externalScriptDomains, List<String> externalStyleDomains,
                              List<String> internalLinks, boolean cookiePresent,
                              List<FormInfo> forms, RenderMode renderMode,
                              List<String> preConsentTrackerHosts,
                              List<ObservedCookie> preConsentCookies,
                              List<String> preConsentStorageKeys,
                              boolean preConsentCookiesSnapshotAvailable,
                              boolean preConsentStorageSnapshotAvailable) {
        this(url, title, text, html, externalScriptDomains, externalStyleDomains, internalLinks,
                cookiePresent, forms, renderMode, preConsentTrackerHosts, preConsentCookies,
                preConsentStorageKeys, preConsentCookiesSnapshotAvailable,
                preConsentStorageSnapshotAvailable, null);
    }
}
