package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.crawler.CrawlerDiagnostics;
import io.okdocs.compliance.contracts.crawler.FormInfo;
import io.okdocs.compliance.contracts.crawler.HttpResponseInfo;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.crawler.TechnicalAnalysisResult;
import io.okdocs.compliance.contracts.crawler.TlsInfo;
import io.okdocs.compliance.contracts.enums.RegistryStatus;
import io.okdocs.compliance.contracts.enums.RenderMode;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;

import java.util.List;
import java.util.Map;

/** Минимальные билдеры для конструирования контекста в unit-тестах правил. */
public final class TestFixtures {

    private TestFixtures() {
    }

    // ── Контекст ─────────────────────────────────────────────────────────────────────────────

    /** Контекст с дефолтным enrichment: hostCountry=RU, registry=FOUND (нарушений по ним нет). */
    public static ScanAnalysisContext ctx(PageAnalysisResult... pages) {
        return ctx("RU", RegistryStatus.FOUND, pages);
    }

    public static ScanAnalysisContext ctx(String hostCountry, RegistryStatus registry,
                                          PageAnalysisResult... pages) {
        return new ScanAnalysisContext(
                ScanJurisdiction.RU,
                List.of(pages),
                hostCountry,
                List.of("1.2.3.4"),
                registry,
                new CrawlerDiagnostics(pages.length, pages.length, 0, false));
    }

    /** Контекст заданной юрисдикции — для проверки multi-layer гейта движка. */
    public static ScanAnalysisContext ctxFor(ScanJurisdiction jurisdiction, PageAnalysisResult... pages) {
        return new ScanAnalysisContext(
                jurisdiction,
                List.of(pages),
                "RU",
                List.of("1.2.3.4"),
                RegistryStatus.FOUND,
                new CrawlerDiagnostics(pages.length, pages.length, 0, false));
    }

    // ── Technical (headers) ─────────────────────────────────────────────────────────────────

    /** Контекст с techical-паспортом из заданных HTTP-ответов (одна fake-страница для hasPages). */
    public static ScanAnalysisContext ctxWithResponses(HttpResponseInfo... responses) {
        return ctxForWithResponses(ScanJurisdiction.RU, responses);
    }

    /** То же, но для заданной юрисдикции — для проверки common-правил на EU/UK/DE-сканах. */
    public static ScanAnalysisContext ctxForWithResponses(ScanJurisdiction jurisdiction,
                                                          HttpResponseInfo... responses) {
        return new ScanAnalysisContext(
                jurisdiction,
                List.of(simplePage("https://site.ru")),
                "RU",
                List.of("1.2.3.4"),
                RegistryStatus.FOUND,
                new CrawlerDiagnostics(1, 1, 0, false),
                new TechnicalAnalysisResult(List.of(responses), List.of(), null));
    }

    /** Финальный 200-ответ с заданными заголовками (имена → значения). */
    public static HttpResponseInfo response(String url, Map<String, String> headers) {
        return response(url, 200, headers);
    }

    public static HttpResponseInfo response(String url, int status, Map<String, String> headers) {
        Map<String, List<String>> multi = new java.util.HashMap<>();
        headers.forEach((k, v) -> multi.put(k.toLowerCase(java.util.Locale.ROOT), List.of(v)));
        return new HttpResponseInfo(url, status, multi, false, null);
    }

    /** 3xx redirect-хоп (исключается из анализа security-заголовков). */
    public static HttpResponseInfo redirect(String url, String location) {
        return new HttpResponseInfo(url, 301, Map.of(), true, location);
    }

    /** Контекст с явными страницами + HTTP-ответами (для правил группы B: form-action, mixed-content). */
    public static ScanAnalysisContext ctxTechnical(List<PageAnalysisResult> pages,
                                                   List<HttpResponseInfo> responses,
                                                   List<TlsInfo> tls) {
        return new ScanAnalysisContext(
                ScanJurisdiction.RU,
                pages,
                "RU",
                List.of("1.2.3.4"),
                RegistryStatus.FOUND,
                new CrawlerDiagnostics(pages.size(), pages.size(), 0, false),
                new TechnicalAnalysisResult(responses, tls, null));
    }

    // ── Technical (TLS) ─────────────────────────────────────────────────────────────────────

    /** Контекст с TLS-паспортом (одна fake-страница для hasPages). */
    public static ScanAnalysisContext ctxWithTls(TlsInfo... tls) {
        return ctxTechnical(List.of(simplePage("https://site.ru")), List.of(), List.of(tls));
    }

    /**
     * Успешный handshake: protocol/SAN/notAfter задаются явно. Зондирование версий считается
     * выполненным — {@code supportedProtocols = [protocol]} (только согласованная версия). Для
     * сценариев с реально принимаемым legacy используйте {@link #tlsOkSupporting}.
     */
    public static TlsInfo tlsOk(String host, String protocol, List<String> san,
                                java.time.Instant notAfter) {
        return tlsOkSupporting(host, protocol, san, notAfter, List.of(protocol));
    }

    /**
     * Успешный handshake с явным набором принимаемых сервером версий ({@code supportedProtocols}).
     * Моделирует «согласовано TLS 1.3, но TLS 1.0/1.1 включены»: probe инспектора выполнен.
     */
    public static TlsInfo tlsOkSupporting(String host, String protocol, List<String> san,
                                          java.time.Instant notAfter, List<String> supportedProtocols) {
        return new TlsInfo(host, true, null, true,
                hostMatchesAny(host, san), false, protocol, "TLS_AES_128_GCM_SHA256",
                "CN=" + host, "CN=Test CA", san,
                java.time.Instant.now().minusSeconds(86400), notAfter, supportedProtocols);
    }

    /** Совпадение host с SAN с поддержкой левого wildcard — повторяет логику TlsInfo для фикстур. */
    private static boolean hostMatchesAny(String host, List<String> names) {
        if (host == null || names == null) {
            return false;
        }
        String h = host.toLowerCase(java.util.Locale.ROOT);
        for (String name : names) {
            if (name == null) {
                continue;
            }
            String n = name.toLowerCase(java.util.Locale.ROOT).trim();
            if (n.startsWith("*.")) {
                int dot = h.indexOf('.');
                if (dot > 0 && h.substring(dot).equals(n.substring(1))) {
                    return true;
                }
            } else if (h.equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** Проваленный handshake с заданной ошибкой. */
    public static TlsInfo tlsFailed(String host, String error) {
        return new TlsInfo(host, false, error, null, null, null, null, List.of(), null, null);
    }

    // ── Technical (DNS) ─────────────────────────────────────────────────────────────────────

    /** Контекст с DNS-паспортом (одна fake-страница для hasPages). */
    public static ScanAnalysisContext ctxWithDns(io.okdocs.compliance.contracts.crawler.DnsInfo dns) {
        return new ScanAnalysisContext(
                ScanJurisdiction.RU,
                List.of(simplePage("https://site.ru")),
                dns == null ? null : dns.hostCountry(),
                dns == null ? List.of() : dns.resolvedIps(),
                RegistryStatus.FOUND,
                new CrawlerDiagnostics(1, 1, 0, false),
                new TechnicalAnalysisResult(List.of(), List.of(), dns));
    }

    /** DnsInfo с явными странами IP / MX / CNAME (host=site.ru, lookupOk). */
    public static io.okdocs.compliance.contracts.crawler.DnsInfo dns(
            List<String> ips, List<String> ipCountries, List<String> cnameChain, List<String> mailCountries) {
        String hostCountry = ipCountries.contains("RU") ? "RU"
                : (ipCountries.isEmpty() ? null : ipCountries.get(0));
        return new io.okdocs.compliance.contracts.crawler.DnsInfo(
                "site.ru", false, hostCountry, ips, ipCountries, cnameChain, List.of(), mailCountries);
    }

    public static io.okdocs.compliance.contracts.crawler.DnsInfo dnsFailed() {
        return new io.okdocs.compliance.contracts.crawler.DnsInfo(
                "site.ru", true, null, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    // ── Страницы ─────────────────────────────────────────────────────────────────────────────

    public static PageAnalysisResult page(String url, String text, boolean cookiePresent,
                                          List<FormInfo> forms, List<String> internalLinks,
                                          List<String> externalScripts, String html) {
        return new PageAnalysisResult(
                url, "title", text, html,
                externalScripts, List.of(), internalLinks,
                cookiePresent, forms, RenderMode.STATIC);
    }

    /** Простая HTML-страница с формами, без cookie-баннера, без трекеров и ссылок на политику. */
    public static PageAnalysisResult simplePage(String url, FormInfo... forms) {
        return page(url, "обычный текст страницы", false, List.of(forms),
                List.of(), List.of(), "<html></html>");
    }

    /** Страница с внешними скриптами. */
    public static PageAnalysisResult pageWithScripts(String url, List<String> externalScripts) {
        return page(url, "текст", false, List.of(), List.of(), externalScripts, "<html></html>");
    }

    /**
     * DYNAMIC-страница с трекер-скриптами И зафиксированным таймлайном «до согласия»
     * ({@code preConsentTrackerHosts}). Баннера нет. Моделирует реальный CDP-проход, где запрос
     * трекера наблюдался раньше cookie-баннера → правило обязано дать CONFIRMED.
     */
    public static PageAnalysisResult dynamicPageWithPreConsent(String url, List<String> externalScripts,
                                                               List<String> preConsentTrackerHosts) {
        return dynamicPageWithPreConsent(url, externalScripts, preConsentTrackerHosts, false);
    }

    public static PageAnalysisResult dynamicPageWithPreConsent(String url, List<String> externalScripts,
                                                               List<String> preConsentTrackerHosts,
                                                               boolean cookiePresent) {
        return new PageAnalysisResult(
                url, "title", "текст", "<html></html>",
                externalScripts, List.of(), List.of(),
                cookiePresent, List.of(), RenderMode.DYNAMIC, preConsentTrackerHosts);
    }

    /** DYNAMIC-страница с наблюдёнными до согласия cookies и ключами localStorage. */
    public static PageAnalysisResult dynamicPageWithCookies(
            String url, List<io.okdocs.compliance.contracts.crawler.ObservedCookie> cookies,
            List<String> storageKeys) {
        return new PageAnalysisResult(
                url, "title", "текст", "<html></html>",
                List.of(), List.of(), List.of(),
                false, List.of(), RenderMode.DYNAMIC, List.of(), cookies, storageKeys, true, true);
    }

    /** DYNAMIC-страница с заданным consent-сценарием (Фаза 5). */
    public static PageAnalysisResult dynamicPageWithConsent(
            String url, io.okdocs.compliance.contracts.crawler.ConsentScenarioResult scenario) {
        return new PageAnalysisResult(
                url, "title", "текст", "<html></html>",
                List.of(), List.of(), List.of(),
                false, List.of(), RenderMode.DYNAMIC, List.of(), List.of(), List.of(), true, true,
                scenario);
    }

    /** Cookie с явными флагами (persistent, не session). */
    public static io.okdocs.compliance.contracts.crawler.ObservedCookie cookie(
            String name, boolean secure, boolean httpOnly) {
        return new io.okdocs.compliance.contracts.crawler.ObservedCookie(
                name, "site.ru", secure, httpOnly, "Lax", false);
    }

    /** Сессионная cookie (нет expires). */
    public static io.okdocs.compliance.contracts.crawler.ObservedCookie sessionCookie(
            String name, boolean secure, boolean httpOnly) {
        return new io.okdocs.compliance.contracts.crawler.ObservedCookie(
                name, "site.ru", secure, httpOnly, "Lax", true);
    }

    // ── Формы ────────────────────────────────────────────────────────────────────────────────

    // FormInfo: action, method, inputNames, hasPasswordField, hasFileUpload, hasCheckbox,
    //           hasConsentText, hasPrivacyPolicyLink, hasDefaultCheckedConsent, hasPdField

    /** Форма, собирающая ПДн (hasPdField=true), без согласия. */
    public static FormInfo dataFormNoConsent(String action) {
        return new FormInfo(action, "POST", List.of("email"),
                false, false, false, false, false, false, true);
    }

    /** Форма с ПДн + текстом согласия (надлежащее согласие). */
    public static FormInfo dataFormWithConsent(String action) {
        return new FormInfo(action, "POST", List.of("email"),
                false, false, true, true, true, false, true);
    }

    /** Форма с ПДн и предотмеченным согласием. */
    public static FormInfo dataFormDefaultChecked(String action) {
        return new FormInfo(action, "POST", List.of("email"),
                false, false, true, true, true, true, true);
    }

//    /** Форма без ПДн-полей (hasPdField=false) — напр. поиск/csrf. */
    public static FormInfo emptyForm(String action) {
        return new FormInfo(action, "GET", List.of("q"),
                false, false, false, false, false, false, false);
    }
}
