package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.contracts.crawler.CrawlerDiagnostics;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import org.slf4j.MDC;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final PinnedHttpFetcher pinnedHttpFetcher;

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

        Map<String, Boolean> hostSafetyCache = new ConcurrentHashMap<>();

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

        BlockingQueue<UrlWithDepth> queue = new LinkedBlockingQueue<>();
        CrawlState state = new CrawlState(startDomain, robots, deadline, maxPages, maxDepth, queue,
                hostSafetyCache);
        state.enqueue(new UrlWithDepth(startUrl, 0));
        seedQueue(state, baseUrl, startDomain, normalizedStart, maxPages, hostSafetyCache);

        runWorkers(state);

        // Стартовая страница (depth 0) всегда первая: selectDynamicTargets берёт pages.get(0) как
        // homepage. Параллельный обход даёт недетерминированный порядок, поэтому ставим её вперёд
        // явно (хранится отдельно в state.startPage), остальное — в порядке завершения.
        List<PageAnalysisResult> ordered = new ArrayList<>();
        PageAnalysisResult start = state.startPage.get();
        if (start != null) {
            ordered.add(start);
        }
        ordered.addAll(state.results);

        boolean timedOut = state.timedOut.get();
        int fetched = ordered.size();
        log.info("SiteCrawler done url={} status={} pages={} attempted={} failed={}",
                startUrl, timedOut ? "PARTIAL" : "COMPLETE", fetched,
                state.attempted.get(), state.failed.get());

        return new CrawlResult(
                List.copyOf(ordered),
                new CrawlerDiagnostics(state.attempted.get(), fetched, state.failed.get(), timedOut));
    }

    /** Запускает пул воркеров и ждёт естественного завершения BFS (очередь пуста И никто не в работе). */
    private void runWorkers(CrawlState state) {
        int workers = Math.max(1, properties.getCrawler().getConcurrency());
        // MDC (scanId/scanKind) живёт на потоке Kafka-листенера; fetch-* — отдельные потоки пула.
        // Снимаем контекст вызывающего потока и ставим в каждом воркере, чтобы static-логи
        // трассировались по scanId, как и весь остальной пайплайн.
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        ExecutorService pool = Executors.newFixedThreadPool(workers,
                r -> new Thread(r, "static-crawler-" + System.nanoTime() % 1000));
        try {
            List<Future<?>> futures = new ArrayList<>(workers);
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> worker(state, parentMdc)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    log.debug("static-crawler worker failed: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                    log.debug("static-crawler pool did not terminate cleanly within 3s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Один воркер: тянет URL из общей очереди, фетчит, кладёт найденные ссылки обратно.
     * <p>
     * Завершение BFS без преждевременного выхода и без зависания строится на едином счётчике
     * {@code pendingWork} = (URL в очереди) + (URL в обработке). Он инкрементируется при КАЖДОМ
     * добавлении в очередь (стартовый URL, seed'ы, найденные ссылки) и декрементируется ровно один
     * раз после полной обработки URL. {@code pendingWork == 0} означает, что работы нет и появиться
     * не может (потомки добавляются до декремента родителя, т.е. пока его единица ещё учтена) —
     * это атомарный признак конца, не зависящий от тайминга poll. Воркеры с пустым poll просто
     * крутятся с коротким таймаутом до {@code pendingWork==0} или {@code stopped}.
     */
    private void worker(CrawlState state, Map<String, String> parentMdc) {
        if (parentMdc != null) {
            MDC.setContextMap(parentMdc);
        }
        try {
            while (!state.stopped.get()
                    && state.accepted.get() < state.maxPages
                    && state.pendingWork.get() > 0) {
                UrlWithDepth current = state.queue.poll(50, TimeUnit.MILLISECONDS);
                if (current == null) {
                    continue;
                }
                try {
                    processUrl(state, current);
                } finally {
                    state.pendingWork.decrementAndGet();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            MDC.clear();
        }
    }

    /** Обработка одного URL: дедлайн/лимит/visited/robots/SSRF → fetch → accept → results + ссылки. */
    private void processUrl(CrawlState state, UrlWithDepth current) throws InterruptedException {
        if (System.currentTimeMillis() > state.deadline) {
            if (state.timedOut.compareAndSet(false, true)) {
                log.warn("SiteCrawler deadline reached, {} pages so far", state.accepted.get());
            }
            state.stopped.set(true);
            return;
        }
        // Терминальная остановка — ТОЛЬКО когда реально ПРИНЯТО maxPages страниц (homepage включён).
        // Это монотонное условие: набрали лимит → больше не нужно. НЕ останавливаемся по «слоты заняты
        // сейчас» — занятые слоты могут вернуться (in-flight упал/soft-404), и тогда оставшиеся URL
        // из очереди должны добраться до fetch.
        if (state.accepted.get() >= state.maxPages) {
            state.stopped.set(true);
            return;
        }
        String normalized = normalizeUrl(current.url());
        if (normalized == null || current.depth() > state.maxDepth) {
            return;
        }
        if (!state.robots.isAllowed(normalized)) {
            log.debug("Skipping disallowed by robots.txt: {}", normalized);
            return;
        }
        // Резервируем слот ДО fetch (атомарный потолок). Если слотов нет, но лимит ещё НЕ принят —
        // часть слотов держат in-flight страницы, которые могут упасть и вернуть слот. Тогда не
        // теряем URL: возвращаем его в очередь (visited ещё не помечен) и уступаем. Если же лимит
        // реально принят — выходим: дальше fetch не нужен.
        if (!state.tryReserveSlot()) {
            if (state.accepted.get() < state.maxPages) {
                state.requeue(current);
                Thread.sleep(10);
            }
            return;
        }
        // visited помечаем ПОСЛЕ удачного резерва слота: иначе URL, отложенный из-за временно занятых
        // слотов, оказался бы помечен visited и потерян при возврате в очередь (баг полноты).
        if (!state.visited.add(normalized)) {
            state.releaseSlot();
            return;
        }
        // SSRF-гард на КАЖДЫЙ URL перед запросом (не только на redirect-хопы): хост seed/ссылки
        // мог за-DNS-резолвиться в приватную сеть. Проверяем в том же процессе, что и fetch.
        if (!isSafeHost(PageExtractor.extractDomain(normalized), state.hostSafetyCache)) {
            log.warn("SSRF blocked url={} (private/blocked host)", normalized);
            state.attempted.incrementAndGet();
            state.failed.incrementAndGet();
            state.releaseSlot();
            return;
        }
        state.attempted.incrementAndGet();

        boolean accepted = false;
        boolean isStart = current.depth() == 0;
        try {
            Document doc = fetchWithRedirectValidation(normalized, state.hostSafetyCache);
            String pageUrl = normalizeUrl(doc.location());
            if (pageUrl == null) {
                pageUrl = normalized;
            }
            PageAnalysisResult page = PageExtractor.extract(pageUrl, doc, state.startDomain);

            if (!isStart && !acceptPage(pageUrl, page, state.acceptedFingerprints)) {
                return;
            }
            accepted = true;
            state.accepted.incrementAndGet(); // монотонный счётчик реально принятых (для лимита/лога)
            if (isStart) {
                // Стартовую страницу помечаем по факту depth==0, а НЕ по совпадению URL: после
                // redirect (http→https/www) её финальный URL ≠ исходный, и сравнение по строке
                // ломало бы инвариант «homepage первая» для selectDynamicTargets.
                state.startPage.set(page);
            } else {
                state.results.add(page);
            }
            for (String link : page.internalLinks()) {
                String normLink = normalizeUrl(link);
                if (normLink != null && !state.visited.contains(normLink)
                        && isCrawlableCandidate(normLink, state.startDomain)) {
                    state.enqueue(new UrlWithDepth(normLink, current.depth() + 1));
                }
            }
            Thread.sleep(properties.getCrawler().getRateLimitMs());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        } catch (SsrfBlockedException e) {
            log.warn("SSRF redirect blocked url={}: {}", normalized, e.getMessage());
            state.failed.incrementAndGet();
        } catch (Exception e) {
            log.warn("Failed to fetch url={}: {}", normalized, e.getClass().getSimpleName());
            state.failed.incrementAndGet();
        } finally {
            // Слот возвращаем, если страница не принята — он достанется другому URL, и суммарно
            // принятых (homepage + остальные) никогда не превысит maxPages.
            if (!accepted) {
                state.releaseSlot();
            }
        }
    }

    /** Разделяемое состояние одного краула между воркерами пула. */
    private static final class CrawlState {
        final String startDomain;
        final RobotsTxt robots;
        final long deadline;
        final int maxPages;
        final int maxDepth;
        final BlockingQueue<UrlWithDepth> queue;
        final Map<String, Boolean> hostSafetyCache;

        final Set<String> visited = ConcurrentHashMap.newKeySet();
        final Set<String> acceptedFingerprints = ConcurrentHashMap.newKeySet();
        // Стартовая страница (depth==0) хранится отдельно, чтобы гарантированно поставить её первой
        // в итог независимо от порядка завершения воркеров и redirect'а финального URL.
        final java.util.concurrent.atomic.AtomicReference<PageAnalysisResult> startPage =
                new java.util.concurrent.atomic.AtomicReference<>();
        final ConcurrentLinkedQueue<PageAnalysisResult> results = new ConcurrentLinkedQueue<>();
        final AtomicInteger attempted = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        // reserved = занятые слоты (in-flight + принятые), ВРЕМЕННЫЙ счётчик: резервируется перед
        // fetch и откатывается releaseSlot при неудаче. Даёт атомарный потолок ≤ maxPages, но НЕ
        // годится как признак «лимит достигнут навсегда» (занятый слот может вернуться).
        final AtomicInteger reserved = new AtomicInteger();
        // accepted = МОНОТОННОЕ число реально принятых страниц (homepage включён). Инкремент только
        // после accepted=true. По нему — терминальная остановка и deadline-лог; назад не идёт.
        final AtomicInteger accepted = new AtomicInteger();
        // = (URL в очереди) + (URL в обработке). pendingWork==0 → работы нет и не будет (атомарный
        // признак конца BFS, не зависящий от тайминга poll).
        final AtomicInteger pendingWork = new AtomicInteger();
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        final AtomicBoolean stopped = new AtomicBoolean(false);

        CrawlState(String startDomain, RobotsTxt robots, long deadline, int maxPages, int maxDepth,
                   BlockingQueue<UrlWithDepth> queue, Map<String, Boolean> hostSafetyCache) {
            this.startDomain = startDomain;
            this.robots = robots;
            this.deadline = deadline;
            this.maxPages = maxPages;
            this.maxDepth = maxDepth;
            this.queue = queue;
            this.hostSafetyCache = hostSafetyCache;
        }

        /** Добавляет НОВЫЙ URL в очередь, учитывая его как незавершённую работу (см. pendingWork). */
        void enqueue(UrlWithDepth item) {
            pendingWork.incrementAndGet();
            queue.add(item);
        }

        /**
         * Возврат URL в очередь, отложенного из-за временно занятых слотов. Балансирует pendingWork:
         * worker.finally декрементит его после processUrl, поэтому здесь инкрементим заново (net 0,
         * элемент снова в очереди), иначе pendingWork «потеряет» отложенный URL и BFS завершится рано.
         */
        void requeue(UrlWithDepth item) {
            pendingWork.incrementAndGet();
            queue.add(item);
        }

        /**
         * Атомарно занимает один слот (reserved ≤ maxPages). false → слоты заняты сейчас (см. accepted
         * для проверки, достигнут ли лимит окончательно). Возврат через {@link #releaseSlot()}.
         */
        boolean tryReserveSlot() {
            while (true) {
                int cur = reserved.get();
                if (cur >= maxPages) {
                    return false;
                }
                if (reserved.compareAndSet(cur, cur + 1)) {
                    return true;
                }
            }
        }

        void releaseSlot() {
            reserved.decrementAndGet();
        }
    }

    /** Сидирование очереди: sitemap → priority hints (без CommonCrawl). */
    private void seedQueue(CrawlState state, String baseUrl, String startDomain,
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
        if (seeds.isEmpty()) {
            return;
        }
        // Не сидируем больше, чем останется слотов после homepage (= maxPages-1): для maxPages=1
        // (free/static-only) seed'ы вовсе не добавляются — нет лишних запросов к /privacy, /contact
        // и ложного PARTIAL из-за pagesFailed>0. Жёсткий потолок принятых страниц обеспечивает
        // reserved-слот в processUrl; здесь — лишь чтобы не плодить заведомо лишний fetch.
        int seedBudget = Math.max(0, maxPages - 1);
        if (seedBudget == 0) {
            return;
        }
        int added = 0;
        for (String candidate : seeds) {
            if (added >= seedBudget) {
                break;
            }
            state.enqueue(new UrlWithDepth(candidate, 1));
            added++;
        }
        log.info("SiteCrawler seeds={} domain={}", added, startDomain);
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
            PinnedHttpFetcher.Response resp = fetchPinned(
                    currentUrl,
                    properties.getCrawler().getPageTimeoutMs(),
                    properties.getCrawler().getMaxBodyBytes());

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
                return Jsoup.parse(resp.body(), currentUrl);
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
        PinnedHttpFetcher.Response resp = fetchPinned(sitemapUrl, 5000, properties.getCrawler().getMaxBodyBytes());
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
            PinnedHttpFetcher.Response resp = fetchPinned(robotsUrl, 5000, properties.getCrawler().getMaxBodyBytes());
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

    private PinnedHttpFetcher.Response fetchPinned(String url, int timeoutMs, long maxBodyBytes) throws IOException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URL: " + url, e);
        }
        UrlValidator.ResolvedHost resolved = urlValidator.resolvePublicHost(uri.getHost());
        if (!resolved.valid()) {
            throw new SsrfBlockedException(resolved.errorMessage());
        }

        int connectTimeoutMs = Math.min(properties.getCrawler().getConnectTimeoutMs(), timeoutMs);
        IOException last = null;
        for (InetAddress address : resolved.addresses()) {
            try {
                return pinnedHttpFetcher.fetch(uri, address, properties.getCrawler().getUserAgent(),
                        connectTimeoutMs, timeoutMs, maxBodyBytes);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("No resolved addresses for " + url) : last;
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
