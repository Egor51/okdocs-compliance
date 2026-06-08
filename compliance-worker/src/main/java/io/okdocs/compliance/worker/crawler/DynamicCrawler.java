package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Контракт краулера с JS-рендерингом (§5.4). DYNAMIC-обогащение для PREMIUM-тира: видит контент,
 * подгруженный скриптами (баннеры согласия, трекеры), который STATIC-Jsoup не отрендерит.
 * <p>
 * Реализация {@link CdpDynamicCrawler} подключается к удалённому Chromium через Chrome DevTools
 * Protocol (WebSocket), без Playwright SDK/Node.js. {@link NoopDynamicCrawler} — заглушка, когда
 * dynamic выключен ({@link #isAvailable()} == false → сервис пропускает re-crawl без ошибки).
 */
public interface DynamicCrawler {

    /** Загружает страницу в headless-браузере; результат с {@code renderMode = DYNAMIC}. */
    PageAnalysisResult crawlPage(String url);

    /**
     * Батч-обход. Реализация по умолчанию — последовательно по одной странице.
     * {@code allowedThirdPartyHosts} — доверенные сторонние хосты (например CDN), которые не режутся
     * доменной политикой.
     */
    default Map<String, PageAnalysisResult> crawlPages(List<String> urls, Set<String> allowedThirdPartyHosts) {
        Map<String, PageAnalysisResult> out = new LinkedHashMap<>();
        if (urls == null) {
            return out;
        }
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            out.put(url, crawlPage(url));
        }
        return out;
    }

    /** {@code true}, если CDP-браузер доступен. При {@code false} сервис пропускает dynamic re-crawl. */
    boolean isAvailable();
}
