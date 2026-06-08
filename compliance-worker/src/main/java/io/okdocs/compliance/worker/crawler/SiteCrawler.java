package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.contracts.crawler.CrawlerDiagnostics;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Jsoup BFS-краулер статического HTML (§5.4). Стратегия сидирования (MVP): sitemap.xml →
 * priority paths ({@code /privacy}, {@code /contact}, {@code /terms}) → BFS с главной. CommonCrawl
 * из okdocks намеренно не переносится (захардкоженный индекс протухает, хрупкая внешняя зависимость).
 * <p>
 * Уважает robots.txt (best-effort), таймауты (per-page / total), ручную обработку redirect-хопов с
 * SSRF-валидацией каждого хопа ({@link UrlValidator#isHostSafe}). Логика обхода перенесена из
 * okdocks; проекция в контрактные records — в {@link PageExtractor}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiteCrawler {

    private static final int MAX_REDIRECT_HOPS = 8;
    private static final int MIN_PAGE_TEXT_LENGTH = 350;
    private static final int MIN_SEED_URLS = 3;

    private static final Pattern UTM_PARAM = Pattern.compile(
            "(?:^|&)(utm_[^=&]+=?[^&]*)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> TRACKING_QUERY_PARAMS = Set.of(
            "gclid", "fbclid", "yclid", "ysclid", "msclkid", "_openstat", "_ga", "_gl",
            "from", "source", "ref", "ref_src", "rb_clickid", "roistat", "roistat_visit",
            "mc_cid", "mc_eid");
    private static final Set<String> NON_HTML_EXTENSIONS = Set.of(
            "js", "mjs", "css", "json", "xml", "txt", "map",
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif",
            "woff", "woff2", "ttf", "eot", "otf",
            "pdf", "zip", "rar", "7z", "tar", "gz",
            "mp4", "webm", "mov", "avi", "mp3", "wav", "ogg", "m4a");
    private static final Pattern PRIORITY_URL_PATTERN = Pattern.compile(
            "(privacy|privacypolicy|privacy-policy|privacy_policy|policy|personal-data|personal_data|"
                    + "cookie-policy|cookies|cookie|consent|agreement-on-data-processing|"
                    + "soglasie-na-obrabotku-personalnyh-dannyh|user-agreement|user_agreement|"
                    + "terms|legal|oferta|publichnaya-oferta|contact|contacts|kontakty|kontaktyi|"
                    + "rekvizity|details|politika-konfidencialnosti|politika-konfidentsialnosti)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern POLICY_CONTACT_TEXT_PATTERN = Pattern.compile(
            "(политик|конфиденц|персональн|обработк|согласи|cookie|privacy|policy|contact|контакт|реквизит)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Пути, которые всегда попадают в краул первыми — нужны для правил (NoPrivacyPolicy/NoOperatorContacts). */
    private static final List<String> PRIORITY_PATH_HINTS = List.of(
            "/privacy", "/privacypolicy", "/privacy-policy", "/personal-data", "/pd",
            "/contact", "/contacts", "/kontakty", "/about", "/o-nas", "/o-kompanii",
            "/terms", "/legal", "/docs/privacy",
            "/privacy_policy", "/privacy-policy.html",
            "/politika-konfidencialnosti", "/politika-konfidentsialnosti",
            "/soglasie-na-obrabotku-personalnyh-dannyh", "/consent", "/agreement-on-data-processing",
            "/user-agreement", "/user_agreement", "/oferta", "/publichnaya-oferta",
            "/rekvizity", "/details", "/cookie-policy", "/cookies", "/policy", "/kontaktyi");

    private final ComplianceWorkerProperties properties;
    private final UrlValidator urlValidator;

    public CrawlResult crawl(String startUrl, int maxPages) {
        var cfg = properties.getCrawler();
        int effectiveMaxPages = maxPages > 0 ? Math.min(maxPages, cfg.getMaxPages()) : cfg.getMaxPages();
        return doCrawl(startUrl, effectiveMaxPages, cfg.getMaxDepth());
    }

    private CrawlResult doCrawl(String startUrl, int maxPages, int maxDepth) {
        String startDomain = PageExtractor.extractDomain(startUrl);
        if (startDomain == null) {
            log.warn("Cannot extract domain from start url {}", startUrl);
            return CrawlResult.failed();
        }

        Set<String> visited = new HashSet<>();
        Deque<UrlWithDepth> queue = new ArrayDeque<>();
        List<PageAnalysisResult> results = new ArrayList<>();
        Set<String> acceptedFingerprints = new HashSet<>();
        Map<String, Boolean> hostSafetyCache = new HashMap<>();

        int attempted = 0;
        int failed = 0;

        // Worker — отдельный trust boundary: api валидировал URL, но DNS мог перепривязаться
        // (DNS rebinding) между валидацией api и fetch'ем worker'а. Перевалидируем стартовый хост
        // здесь, в том же процессе, что делает запрос; небезопасный → весь краул отклоняется.
        if (!isSafeHost(startDomain, hostSafetyCache)) {
            log.warn("SiteCrawler start host unsafe (SSRF), aborting: {}", startDomain);
            return CrawlResult.failed();
        }

        log.info("SiteCrawler start url={} maxPages={} maxDepth={}", startUrl, maxPages, maxDepth);
        RobotsTxt robots = loadRobotsTxt(startUrl, hostSafetyCache);
        long deadline = System.currentTimeMillis()
                + properties.getCrawler().getCrawlerTimeoutSeconds() * 1000L;

        String baseUrl = extractBaseUrl(startUrl);
        String normalizedStart = normalizeUrl(startUrl);
        queue.add(new UrlWithDepth(startUrl, 0));

        seedQueue(queue, baseUrl, startDomain, normalizedStart, maxPages, hostSafetyCache);

        boolean timedOut = false;
        while (!queue.isEmpty() && results.size() < maxPages) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("SiteCrawler deadline reached, {} pages so far", results.size());
                timedOut = true;
                break;
            }
            UrlWithDepth current = queue.poll();
            String normalized = normalizeUrl(current.url());
            if (normalized == null || visited.contains(normalized) || current.depth() > maxDepth) {
                continue;
            }
            if (!robots.isAllowed(normalized)) {
                log.debug("Skipping disallowed by robots.txt: {}", normalized);
                continue;
            }
            visited.add(normalized);

            // SSRF-гард на КАЖДЫЙ URL перед запросом (не только на redirect-хопы): хост seed/ссылки
            // мог за-DNS-резолвиться в приватную сеть. Проверяем в том же процессе, что и fetch.
            if (!isSafeHost(PageExtractor.extractDomain(normalized), hostSafetyCache)) {
                log.warn("SSRF blocked url={} (private/blocked host)", normalized);
                attempted++;
                failed++;
                continue;
            }
            attempted++;

            try {
                Document doc = fetchWithRedirectValidation(normalized, hostSafetyCache);
                PageAnalysisResult page = PageExtractor.extract(normalized, doc, startDomain);

                if (current.depth() > 0 && !acceptPage(normalized, page, acceptedFingerprints)) {
                    continue;
                }
                results.add(page);
                for (String link : page.internalLinks()) {
                    String normLink = normalizeUrl(link);
                    if (normLink != null && !visited.contains(normLink)
                            && isCrawlableCandidate(normLink, startDomain)) {
                        queue.add(new UrlWithDepth(normLink, current.depth() + 1));
                    }
                }
                Thread.sleep(properties.getCrawler().getRateLimitMs());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (SsrfBlockedException e) {
                log.warn("SSRF redirect blocked url={}: {}", normalized, e.getMessage());
                failed++;
            } catch (Exception e) {
                log.warn("Failed to fetch url={}: {}", normalized, e.getClass().getSimpleName());
                failed++;
            }
        }

        log.info("SiteCrawler done url={} status={} pages={} attempted={} failed={}",
                startUrl, timedOut ? "PARTIAL" : "COMPLETE", results.size(), attempted, failed);

        return new CrawlResult(
                List.copyOf(results),
                new CrawlerDiagnostics(attempted, results.size(), failed, timedOut));
    }

    /** Сидирование очереди: sitemap → priority hints (без CommonCrawl). */
    private void seedQueue(Deque<UrlWithDepth> queue, String baseUrl, String startDomain,
                           String normalizedStart, int maxPages, Map<String, Boolean> hostSafetyCache) {
        LinkedHashSet<String> seeds = new LinkedHashSet<>();
        if (baseUrl != null) {
            for (String u : loadSitemapUrls(baseUrl, startDomain, maxPages, hostSafetyCache)) {
                String candidate = normalizeUrl(u);
                if (candidate != null && !candidate.equals(normalizedStart)
                        && isCrawlableCandidate(candidate, startDomain)) {
                    seeds.add(candidate);
                }
            }
        }
        if (seeds.size() < MIN_SEED_URLS && baseUrl != null) {
            for (String hint : PRIORITY_PATH_HINTS) {
                String candidate = normalizeUrl(baseUrl + hint);
                if (candidate != null && !candidate.equals(normalizedStart)
                        && isCrawlableCandidate(candidate, startDomain)) {
                    seeds.add(candidate);
                }
            }
        }
        if (!seeds.isEmpty()) {
            log.info("SiteCrawler seeds={} domain={}", seeds.size(), startDomain);
            for (String candidate : seeds) {
                queue.add(new UrlWithDepth(candidate, 1));
            }
        }
    }

    /** Отбраковка soft-404 / дубликатов для не-стартовых страниц. */
    private static boolean acceptPage(String url, PageAnalysisResult page, Set<String> fingerprints) {
        int textLen = page.text() == null ? 0 : page.text().length();
        if (textLen < MIN_PAGE_TEXT_LENGTH && !isShortButUsefulPage(url, page)) {
            log.debug("Skipping soft-404/empty url={} text-len={}", url, textLen);
            return false;
        }
        String fingerprint = contentFingerprint(page);
        if (fingerprint != null && !fingerprints.add(fingerprint)) {
            log.debug("Skipping duplicate-content url={}", url);
            return false;
        }
        return true;
    }

    /** Ручная обработка редиректов с SSRF-валидацией каждого хопа. */
    private Document fetchWithRedirectValidation(String startUrl, Map<String, Boolean> hostSafetyCache)
            throws IOException {
        String currentUrl = startUrl;
        for (int hop = 0; hop < MAX_REDIRECT_HOPS; hop++) {
            Connection.Response resp = Jsoup.connect(currentUrl)
                    .userAgent(properties.getCrawler().getUserAgent())
                    .maxBodySize((int) Math.min(Integer.MAX_VALUE, properties.getCrawler().getMaxBodyBytes()))
                    .timeout(properties.getCrawler().getPageTimeoutMs())
                    .followRedirects(false)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .execute();

            int status = resp.statusCode();
            if (status >= 300 && status < 400) {
                String location = resp.header("Location");
                if (location == null || location.isBlank()) {
                    throw new IOException("Empty Location header on redirect from " + currentUrl);
                }
                String nextUrl = resolveUrl(currentUrl, location);
                String host = PageExtractor.extractDomain(nextUrl);
                String hostLower = host == null ? null : host.toLowerCase(Locale.ROOT);
                boolean safe = hostLower != null
                        && hostSafetyCache.computeIfAbsent(hostLower, urlValidator::isHostSafe);
                if (!safe) {
                    throw new SsrfBlockedException("redirect hop blocked: " + host);
                }
                currentUrl = nextUrl;
            } else if (status == 200) {
                return resp.parse();
            } else {
                throw new IOException("HTTP " + status + " for " + currentUrl);
            }
        }
        throw new IOException("Too many redirects (>" + MAX_REDIRECT_HOPS + ") for " + startUrl);
    }

    private List<String> loadSitemapUrls(String baseUrl, String domain, int maxUrls,
                                         Map<String, Boolean> hostSafetyCache) {
        List<String> candidates = List.of(
                baseUrl + "/sitemap.xml",
                baseUrl + "/sitemap_index.xml",
                baseUrl + "/sitemap/sitemap.xml");
        for (String sitemapUrl : candidates) {
            try {
                List<String> urls = parseSitemap(sitemapUrl, domain, maxUrls, hostSafetyCache);
                if (!urls.isEmpty()) {
                    return urls;
                }
            } catch (Exception e) {
                log.debug("Sitemap not available url={}: {}", sitemapUrl, e.getClass().getSimpleName());
            }
        }
        return List.of();
    }

    private List<String> parseSitemap(String sitemapUrl, String domain, int maxUrls,
                                      Map<String, Boolean> hostSafetyCache) throws IOException {
        // SSRF-гард перед fetch: sitemap-index может ссылаться на дочерний sitemap у чужого хоста.
        if (!isSafeHost(PageExtractor.extractDomain(sitemapUrl), hostSafetyCache)) {
            return List.of();
        }
        Connection.Response resp = Jsoup.connect(sitemapUrl)
                .userAgent(properties.getCrawler().getUserAgent()).timeout(5000)
                .ignoreContentType(true).ignoreHttpErrors(true).execute();
        if (resp.statusCode() != 200) {
            return List.of();
        }
        String body = resp.body();
        if (body == null || body.isBlank()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        if (body.contains("<sitemapindex")) {
            Document doc = Jsoup.parse(body, sitemapUrl, Parser.xmlParser());
            for (Element loc : doc.select("sitemap > loc")) {
                String childUrl = loc.text().trim();
                if (childUrl.isBlank()) {
                    continue;
                }
                try {
                    result.addAll(parseSitemap(childUrl, domain, maxUrls, hostSafetyCache));
                    if (result.size() >= maxUrls) {
                        break;
                    }
                } catch (Exception e) {
                    log.debug("Child sitemap failed url={}: {}", childUrl, e.getClass().getSimpleName());
                }
            }
            return prioritizeAndLimit(result, maxUrls);
        }

        Document doc = Jsoup.parse(body, sitemapUrl, Parser.xmlParser());
        for (Element loc : doc.select("url > loc")) {
            String u = loc.text().trim();
            if (u.isBlank()) {
                continue;
            }
            if (!PageExtractor.isSameDomainOrSubdomain(PageExtractor.extractDomain(u), domain)) {
                continue;
            }
            String normalized = normalizeUrl(u);
            if (normalized != null && isLikelyHtmlPageUrl(normalized)) {
                result.add(normalized);
            }
        }
        return prioritizeAndLimit(result, maxUrls);
    }

    private static List<String> prioritizeAndLimit(List<String> urls, int maxUrls) {
        List<String> priority = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String u : urls) {
            if (PRIORITY_URL_PATTERN.matcher(u).find()) {
                priority.add(u);
            } else {
                rest.add(u);
            }
        }
        List<String> ordered = new ArrayList<>(priority);
        ordered.addAll(rest);
        return ordered.size() <= maxUrls ? ordered : ordered.subList(0, maxUrls);
    }

    private RobotsTxt loadRobotsTxt(String startUrl, Map<String, Boolean> hostSafetyCache) {
        // Явная настройка поведения robots: выключено → не грузим и не ограничиваем (allowAll).
        if (!properties.getCrawler().isRespectRobots()) {
            log.debug("robots.txt disabled by config — allowAll");
            return RobotsTxt.allowAll();
        }
        try {
            URI uri = new URI(startUrl);
            // SSRF-гард перед сетевым запросом robots.txt (тот же процесс делает fetch).
            if (!isSafeHost(uri.getHost(), hostSafetyCache)) {
                return RobotsTxt.allowAll();
            }
            String robotsUrl = uri.getScheme() + "://" + uri.getAuthority() + "/robots.txt";
            Connection.Response resp = Jsoup.connect(robotsUrl)
                    .userAgent(properties.getCrawler().getUserAgent()).timeout(5000)
                    .ignoreContentType(true).ignoreHttpErrors(true).execute();
            if (resp.statusCode() != 200) {
                return RobotsTxt.allowAll();
            }
            return RobotsTxt.parse(resp.body());
        } catch (Exception e) {
            log.debug("robots.txt not loaded for {}: {}", startUrl, e.getClass().getSimpleName());
            return RobotsTxt.allowAll();
        }
    }

    /** SSRF-проверка хоста с кэшем (один DNS-резолв на хост за краул). null/blocked → unsafe. */
    private boolean isSafeHost(String host, Map<String, Boolean> hostSafetyCache) {
        if (host == null || host.isBlank()) {
            return false;
        }
        return hostSafetyCache.computeIfAbsent(host.toLowerCase(Locale.ROOT), urlValidator::isHostSafe);
    }

    /** Нормализация URL: убираем fragment, utm_* и tracking-параметры. */
    static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        try {
            URI raw = new URI(trimmed);
            String scheme = raw.getScheme();
            String host = raw.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            String lowerScheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
                return null;
            }
            String path = (raw.getRawPath() == null || raw.getRawPath().isBlank()) ? "/" : raw.getRawPath();
            String normalizedQuery = normalizeQuery(raw.getRawQuery());
            return new URI(lowerScheme, raw.getRawUserInfo(), host.toLowerCase(Locale.ROOT),
                    raw.getPort(), path, normalizedQuery, null).normalize().toString();
        } catch (URISyntaxException e) {
            int hash = trimmed.indexOf('#');
            if (hash >= 0) {
                trimmed = trimmed.substring(0, hash);
            }
            int q = trimmed.indexOf('?');
            if (q >= 0) {
                String base = trimmed.substring(0, q);
                String cleaned = UTM_PARAM.matcher(trimmed.substring(q + 1)).replaceAll("")
                        .replaceAll("^&+|&+$", "").replaceAll("&&+", "&");
                trimmed = cleaned.isBlank() ? base : base + "?" + cleaned;
            }
            return trimmed.isBlank() ? null : trimmed;
        }
    }

    private static String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String part : rawQuery.split("&")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String key = part;
            int eq = part.indexOf('=');
            if (eq >= 0) {
                key = part.substring(0, eq);
            }
            String keyLower = key.toLowerCase(Locale.ROOT);
            if (keyLower.startsWith("utm_") || TRACKING_QUERY_PARAMS.contains(keyLower)) {
                continue;
            }
            kept.add(part);
        }
        return kept.isEmpty() ? null
                : kept.stream().filter(Objects::nonNull).collect(Collectors.joining("&"));
    }

    private static String resolveUrl(String base, String location) {
        try {
            return new URI(base).resolve(new URI(location)).toString();
        } catch (URISyntaxException e) {
            return location;
        }
    }

    private static String extractBaseUrl(String url) {
        try {
            URI uri = new URI(url);
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean isCrawlableCandidate(String normalizedUrl, String startDomain) {
        if (normalizedUrl == null || startDomain == null) {
            return false;
        }
        String host = PageExtractor.extractDomain(normalizedUrl);
        return PageExtractor.isSameDomainOrSubdomain(host, startDomain) && isLikelyHtmlPageUrl(normalizedUrl);
    }

    private static boolean isLikelyHtmlPageUrl(String normalizedUrl) {
        try {
            URI uri = new URI(normalizedUrl);
            String path = uri.getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                return true;
            }
            int slash = path.lastIndexOf('/');
            String leaf = slash >= 0 ? path.substring(slash + 1) : path;
            if (leaf.isBlank() || !leaf.contains(".")) {
                return true;
            }
            int dot = leaf.lastIndexOf('.');
            if (dot < 0 || dot == leaf.length() - 1) {
                return true;
            }
            String ext = leaf.substring(dot + 1).toLowerCase(Locale.ROOT);
            return !NON_HTML_EXTENSIONS.contains(ext);
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isShortButUsefulPage(String normalizedUrl, PageAnalysisResult page) {
        int formCount = page.forms() == null ? 0 : page.forms().size();
        if (formCount > 0) {
            return true;
        }
        String text = page.text();
        boolean hasPolicyOrContactText = text != null && POLICY_CONTACT_TEXT_PATTERN.matcher(text).find();
        if (PRIORITY_URL_PATTERN.matcher(normalizedUrl).find()) {
            return hasPolicyOrContactText;
        }
        return hasPolicyOrContactText;
    }

    private static String contentFingerprint(PageAnalysisResult page) {
        if (page == null || page.text() == null) {
            return null;
        }
        String normalized = page.text().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        String head = normalized.length() > 400 ? normalized.substring(0, 400) : normalized;
        return page.title() + "|" + normalized.length() + "|" + head.hashCode();
    }

    static final class SsrfBlockedException extends IOException {
        SsrfBlockedException(String message) {
            super(message);
        }
    }

    private record UrlWithDepth(String url, int depth) {
    }

    /** Результат обхода: страницы для движка правил + метрики (§1.6). */
    public record CrawlResult(List<PageAnalysisResult> pages, CrawlerDiagnostics diagnostics) {

        public static CrawlResult failed() {
            return new CrawlResult(List.of(), new CrawlerDiagnostics(0, 0, 0, false));
        }
    }
}
