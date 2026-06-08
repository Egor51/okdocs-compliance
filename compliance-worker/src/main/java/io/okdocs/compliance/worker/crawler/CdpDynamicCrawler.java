package io.okdocs.compliance.worker.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.enums.RenderMode;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DYNAMIC-краулер, подключающийся к удалённому Chromium напрямую через Chrome DevTools Protocol
 * (WebSocket) — без Playwright SDK/Node.js, только встроенный {@link HttpClient} (Java 11+).
 * Перенос проверенной реализации из MVP okdocks; проекция DOM → контрактный
 * {@link PageAnalysisResult} делегирована {@link PageExtractor} (с {@link RenderMode#DYNAMIC}),
 * так что DYNAMIC и STATIC дают одинаковый набор полей для движка правил.
 * <p>
 * Активируется при {@code compliance.crawler.dynamic.enabled=true}; CDP-эндпоинт и токен —
 * {@code compliance.crawler.dynamic.base-url}/{@code .auth-token}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "compliance.crawler.dynamic.enabled", havingValue = "true")
public class CdpDynamicCrawler implements DynamicCrawler {

    private static final long AVAILABILITY_RECHECK_INTERVAL_MS = 10_000;

    /** UA для headless-прохода: настроенный базовый + суффикс, чтобы отличать DYNAMIC от STATIC в логах сайта. */
    private final String userAgent;
    private final String chromiumBaseUrl;
    private final String authHeader;
    private final int pageTimeoutMs;
    private final int concurrency;
    private final int batchTimeoutSeconds;
    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final UrlValidator urlValidator;
    private final AtomicBoolean available = new AtomicBoolean(false);
    private final AtomicLong lastAvailabilityCheckMs = new AtomicLong(0);

    public CdpDynamicCrawler(ComplianceWorkerProperties properties, ObjectMapper objectMapper,
                             UrlValidator urlValidator) {
        var dyn = properties.getCrawler().getDynamic();
        this.userAgent = properties.getCrawler().getUserAgent() + " CDP/headless";
        this.chromiumBaseUrl = dyn.getBaseUrl();
        this.authHeader = "Bearer " + dyn.getAuthToken();
        this.pageTimeoutMs = dyn.getPageTimeoutMs();
        this.concurrency = Math.max(1, dyn.getConcurrency());
        this.batchTimeoutSeconds = dyn.getBatchTimeoutSeconds();
        this.objectMapper = objectMapper;
        this.urlValidator = urlValidator;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        checkAvailability(true);
    }

    private HttpRequest cdpGet(String path, Duration timeout) {
        return HttpRequest.newBuilder()
                .uri(URI.create(chromiumBaseUrl + path))
                .header("Authorization", authHeader)
                .timeout(timeout)
                .GET().build();
    }

    private boolean checkAvailability(boolean force) {
        long now = System.currentTimeMillis();
        long last = lastAvailabilityCheckMs.get();
        boolean firstCheck = last == 0;
        if (!force && (now - last) < AVAILABILITY_RECHECK_INTERVAL_MS) {
            return available.get();
        }
        lastAvailabilityCheckMs.set(now);

        boolean isNowAvailable = false;
        Integer statusCode = null;
        String errorMessage = null;
        try {
            HttpResponse<String> r = http.send(
                    cdpGet("/json/version", Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString());
            statusCode = r.statusCode();
            isNowAvailable = statusCode == 200;
        } catch (Exception e) {
            errorMessage = e.getMessage();
        }

        boolean wasAvailable = available.getAndSet(isNowAvailable);
        if (isNowAvailable && (!wasAvailable || firstCheck)) {
            log.info("CdpDynamicCrawler ready, endpoint={}", chromiumBaseUrl);
        } else if (!isNowAvailable && (wasAvailable || firstCheck)) {
            if (statusCode != null) {
                log.warn("CdpDynamicCrawler /json/version returned HTTP {}", statusCode);
            } else {
                log.warn("CdpDynamicCrawler unavailable: {}", errorMessage);
            }
        }
        return isNowAvailable;
    }

    @Override
    public boolean isAvailable() {
        return checkAvailability(false);
    }

    @Override
    public PageAnalysisResult crawlPage(String url) {
        Map<String, PageAnalysisResult> pages = crawlPages(List.of(url), Set.of());
        PageAnalysisResult page = pages.get(url);
        if (page == null) {
            throw new RuntimeException("CDP crawl failed: " + url);
        }
        return page;
    }

    @Override
    public Map<String, PageAnalysisResult> crawlPages(List<String> urls, Set<String> allowedThirdPartyHosts) {
        if (urls == null || urls.isEmpty()) {
            return new LinkedHashMap<>();
        }
        if (!checkAvailability(true)) {
            throw new IllegalStateException("CDP not initialized: " + chromiumBaseUrl);
        }

        List<String> normalizedUrls = urls.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        if (normalizedUrls.isEmpty()) {
            return new LinkedHashMap<>();
        }

        long t0 = System.currentTimeMillis();

        // Один BrowserContext на весь batch: изолируем от других вкладок, но параллельные Target-ы
        // внутри делят cookies/storage (нам подходит).
        String browserContextId = createBrowserContext();
        try {
            return crawlWithContext(normalizedUrls, allowedThirdPartyHosts, browserContextId, t0);
        } finally {
            disposeBrowserContext(browserContextId);
        }
    }

    private String createBrowserContext() {
        try {
            String browserWs = getBrowserWsUrl();
            try (CdpSession browser = new CdpSession(browserWs, authHeader)) {
                JsonNode result = browser.send("Target.createBrowserContext", null);
                return result.path("browserContextId").asText();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create BrowserContext", e);
        }
    }

    private void disposeBrowserContext(String browserContextId) {
        if (browserContextId == null || browserContextId.isBlank()) {
            return;
        }
        try {
            String browserWs = getBrowserWsUrl();
            try (CdpSession browser = new CdpSession(browserWs, authHeader)) {
                browser.send("Target.disposeBrowserContext",
                        objectMapper.createObjectNode()
                                .put("browserContextId", browserContextId).toString());
            }
        } catch (Exception e) {
            log.debug("disposeBrowserContext ctx={} failed: {}", browserContextId, e.getMessage());
        }
    }

    private Map<String, PageAnalysisResult> crawlWithContext(
            List<String> urls,
            Set<String> allowedThirdPartyHosts,
            String browserContextId,
            long t0) {

        BlockingQueue<String> queue = new LinkedBlockingQueue<>(urls);
        ConcurrentHashMap<String, PageAnalysisResult> results = new ConcurrentHashMap<>();
        // Единый флаг отмены: воркеры проверяют его на каждой итерации и после InterruptedException —
        // так deadline гарантированно жёсткий.
        AtomicBoolean cancelled = new AtomicBoolean(false);

        int workers = Math.min(concurrency, urls.size());
        ExecutorService pool = Executors.newFixedThreadPool(workers,
                r -> new Thread(r, "cdp-worker-" + System.nanoTime() % 1000));
        try {
            List<Future<?>> futures = new ArrayList<>(workers);
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(
                        () -> runWorker(queue, results, allowedThirdPartyHosts, browserContextId, cancelled)));
            }

            long deadlineMs = batchTimeoutSeconds > 0
                    ? batchTimeoutSeconds * 1000L - (System.currentTimeMillis() - t0)
                    : Long.MAX_VALUE;

            for (Future<?> f : futures) {
                try {
                    if (deadlineMs <= 0) {
                        f.cancel(true);
                    } else {
                        long waitStart = System.currentTimeMillis();
                        f.get(deadlineMs, TimeUnit.MILLISECONDS);
                        deadlineMs -= (System.currentTimeMillis() - waitStart);
                    }
                } catch (TimeoutException e) {
                    log.warn("CDP batch deadline reached after {}ms, cancelling remaining workers",
                            System.currentTimeMillis() - t0);
                    cancelled.set(true);
                    queue.clear();
                    futures.forEach(fut -> fut.cancel(true));
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelled.set(true);
                    queue.clear();
                    futures.forEach(fut -> fut.cancel(true));
                    break;
                } catch (ExecutionException e) {
                    log.warn("CDP worker failed: {}", e.getCause() == null
                            ? e.getClass().getSimpleName() : e.getCause().getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                    log.debug("CDP worker pool did not terminate cleanly within 3s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.debug("CDP batch done urls={} ok={} cancelled={} ms={}",
                urls.size(), results.size(), cancelled.get(), System.currentTimeMillis() - t0);

        // Сохраняем порядок входного списка
        Map<String, PageAnalysisResult> ordered = new LinkedHashMap<>();
        for (String url : urls) {
            PageAnalysisResult r = results.get(url);
            if (r != null) {
                ordered.put(url, r);
            }
        }
        return ordered;
    }

    /**
     * Один воркер: создаёт Target (вкладку) внутри browserContext, забирает URL из очереди, обходит
     * последовательно в одной CdpSession, затем закрывает Target. Стоп при interrupt/cancelled.
     */
    private void runWorker(BlockingQueue<String> queue,
                           ConcurrentHashMap<String, PageAnalysisResult> results,
                           Set<String> allowedThirdPartyHosts,
                           String browserContextId,
                           AtomicBoolean cancelled) {
        String targetId = null;
        try {
            String[] target = createTargetInContext(browserContextId);
            targetId = target[0];
            String wsUrl = target[1];

            try (CdpSession s = new CdpSession(wsUrl, authHeader)) {
                s.send("Network.enable", null);
                s.send("Network.setUserAgentOverride",
                        objectMapper.createObjectNode().put("userAgent", userAgent).toString());
                s.send("Page.enable", null);
                s.send("Fetch.enable",
                        objectMapper.createObjectNode()
                                .put("handleAuthRequests", false)
                                .set("patterns", objectMapper.createArrayNode()
                                        .add(objectMapper.createObjectNode().put("urlPattern", "*")))
                                .toString());

                String url;
                while ((url = queue.poll()) != null) {
                    if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    long pageStart = System.currentTimeMillis();
                    try {
                        String[] result = fetchPage(s, url, allowedThirdPartyHosts);
                        long ms = System.currentTimeMillis() - pageStart;
                        log.info("CDP crawl ok url={} finalUrl={} html-len={} ms={}",
                                url, result[0], result[2].length(), ms);
                        results.put(url, buildResult(result[0], result[2]));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        long ms = System.currentTimeMillis() - pageStart;
                        log.warn("CDP crawl failed url={} ms={} err={}", url, ms,
                                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("CDP worker setup failed: {}", e.getMessage());
        } finally {
            if (targetId != null) {
                closeTarget(targetId);
            }
        }
    }

    /** Создаёт Target внутри BrowserContext. Возвращает [targetId, wsDebuggerUrl]. */
    private String[] createTargetInContext(String browserContextId) throws Exception {
        String browserWs = getBrowserWsUrl();
        try (CdpSession browser = new CdpSession(browserWs, authHeader)) {
            JsonNode tgtResult = browser.send("Target.createTarget",
                    objectMapper.createObjectNode()
                            .put("url", "about:blank")
                            .put("browserContextId", browserContextId)
                            .toString());
            String targetId = tgtResult.path("targetId").asText();

            HttpResponse<String> listResp = http.send(
                    cdpGet("/json/list", Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode targets = objectMapper.readTree(listResp.body());
            String wsUrl = "";
            for (JsonNode t : targets) {
                if (targetId.equals(t.path("id").asText())) {
                    wsUrl = resolveWsUrl(t.path("webSocketDebuggerUrl").asText());
                    break;
                }
            }
            if (wsUrl.isBlank()) {
                throw new IllegalStateException("wsDebuggerUrl not found for targetId=" + targetId);
            }
            return new String[]{targetId, wsUrl};
        }
    }

    private void closeTarget(String targetId) {
        try {
            String browserWs = getBrowserWsUrl();
            try (CdpSession browser = new CdpSession(browserWs, authHeader)) {
                browser.send("Target.closeTarget",
                        objectMapper.createObjectNode().put("targetId", targetId).toString());
            }
        } catch (Exception e) {
            log.debug("closeTarget targetId={} failed: {}", targetId, e.getMessage());
        }
    }

    // ── CDP HTTP helpers ──────────────────────────────────────────────────────

    private String getBrowserWsUrl() throws Exception {
        HttpResponse<String> r = http.send(
                cdpGet("/json/version", Duration.ofSeconds(5)),
                HttpResponse.BodyHandlers.ofString());
        JsonNode v = objectMapper.readTree(r.body());
        return resolveWsUrl(v.path("webSocketDebuggerUrl").asText());
    }

    /**
     * Chrome возвращает {@code ws://localhost:PORT} — заменяем host/port на настроенный, чтобы
     * работало из другого Docker-контейнера.
     */
    private String resolveWsUrl(String wsUrl) {
        try {
            URI configured = URI.create(chromiumBaseUrl);
            URI ws = URI.create(wsUrl);
            int port = configured.getPort() > 0 ? configured.getPort() : ws.getPort();
            return new URI("ws", null, configured.getHost(), port,
                    ws.getPath(), ws.getQuery(), null).toString();
        } catch (URISyntaxException e) {
            return wsUrl;
        }
    }

    // ── CDP WebSocket page fetch ──────────────────────────────────────────────

    private String[] fetchPage(CdpSession s, String targetUrl, Set<String> allowedThirdPartyHosts) throws Exception {
        String allowedDomain = extractDomain(targetUrl);
        s.setDomainPolicy(allowedDomain, allowedThirdPartyHosts);
        try {
            s.send("Page.navigate",
                    objectMapper.createObjectNode().put("url", targetUrl).toString());
            s.waitForEvent("Page.loadEventFired", pageTimeoutMs);

            // Быстрый снимок динамики: ждём короткую "тишину", чтобы не блокироваться на long-polling.
            s.waitForNetworkIdle(800, 2000);

            try {
                s.send("Page.stopLoading", null);
            } catch (Exception ignored) {
                // best-effort
            }

            // Один round-trip: собираем url+title+html одним JS-вызовом.
            String json = s.eval(
                    "(function(){var r={u:window.location.href,t:document.title,"
                            + "h:document.documentElement.outerHTML};return JSON.stringify(r);})()");
            JsonNode node = objectMapper.readTree(json);
            return new String[]{
                    node.path("u").asText(""),
                    node.path("t").asText(""),
                    node.path("h").asText("")};
        } finally {
            s.clearDomainPolicy();
        }
    }

    // ── CdpSession (WebSocket) ────────────────────────────────────────────────

    private final class CdpSession implements AutoCloseable {
        private final WebSocket ws;
        private final AtomicInteger ids = new AtomicInteger(0);
        private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();
        private final StringBuilder buf = new StringBuilder();
        private final AtomicInteger inflightRequests = new AtomicInteger(0);
        // null = "browser-level сессия" (createBrowserContext/closeTarget) — фильтрация не нужна
        private volatile String allowedDomain;
        private volatile Set<String> allowedThirdPartyHosts = Set.of();
        private final Set<String> blockedHostsLogged = ConcurrentHashMap.newKeySet();
        // Кэш SSRF-вердикта по хосту: isHostSafe резолвит DNS блокирующе на dispatch-потоке —
        // запоминаем результат, чтобы каждый уникальный хост проверять не более раза за сессию.
        private final ConcurrentHashMap<String, Boolean> hostSafetyCache = new ConcurrentHashMap<>();
        // Сериализует все WebSocket-отправки — Java WebSocket запрещает конкурентные sendText.
        private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();

        CdpSession(String wsUrl, String authorizationHeader) throws Exception {
            this.ws = http.newWebSocketBuilder()
                    .header("Authorization", authorizationHeader)
                    .buildAsync(URI.create(wsUrl), new Listener())
                    .get(10, TimeUnit.SECONDS);
            this.ws.request(Long.MAX_VALUE);
        }

        void setDomainPolicy(String allowedDomain, Set<String> allowedThirdPartyHosts) {
            this.allowedDomain = allowedDomain == null ? null : allowedDomain.toLowerCase(Locale.ROOT);
            this.allowedThirdPartyHosts = normalizeHostSet(allowedThirdPartyHosts);
            this.blockedHostsLogged.clear();
            this.hostSafetyCache.clear();
        }

        void clearDomainPolicy() {
            this.allowedDomain = null;
            this.allowedThirdPartyHosts = Set.of();
            this.blockedHostsLogged.clear();
            this.hostSafetyCache.clear();
        }

        /** Отправляет CDP-команду и ждёт ответа. Потокобезопасна. */
        JsonNode send(String method, String params) throws Exception {
            int id = ids.incrementAndGet();
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            pending.put(id, f);
            String msg = params != null
                    ? "{\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}"
                    : "{\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":{}}";
            sendExecutor.submit(() -> ws.sendText(msg, true).join());
            long timeoutMs = Math.max(5_000L, pageTimeoutMs + 3_000L);
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                pending.remove(id);
                throw e;
            }
        }

        /** Fire-and-forget отправка (для Fetch.continueRequest / Fetch.failRequest из listener). */
        void sendAsync(String msg) {
            sendExecutor.submit(() -> ws.sendText(msg, true).join());
        }

        /** Ждёт "тишины" сети quietMs мс подряд, но не дольше timeoutMs. */
        void waitForNetworkIdle(int quietMs, int timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            long quietSince = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline) {
                if (inflightRequests.get() == 0
                        && (System.currentTimeMillis() - quietSince) >= quietMs) {
                    return;
                }
                if (inflightRequests.get() > 0) {
                    quietSince = System.currentTimeMillis();
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            log.debug("CDP waitForNetworkIdle timed out after {}ms", timeoutMs);
        }

        void waitForEvent(String event, int timeoutMs) throws InterruptedException {
            CompletableFuture<Void> f = new CompletableFuture<>();
            waiters.put(event, f);
            try {
                f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.debug("CDP waitForEvent timeout: {}", event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.debug("CDP waitForEvent error: {}", e.getMessage());
            } finally {
                waiters.remove(event);
            }
        }

        String eval(String expression) throws Exception {
            String safe = expression.replace("\\", "\\\\").replace("\"", "\\\"");
            JsonNode result = send("Runtime.evaluate",
                    "{\"expression\":\"" + safe + "\",\"returnByValue\":true}");
            return result.path("result").path("value").asText("");
        }

        @Override
        public void close() {
            try {
                sendExecutor.submit(() -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join())
                        .get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best-effort close
            }
            sendExecutor.shutdownNow();
        }

        private final class Listener implements WebSocket.Listener {
            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                synchronized (buf) {
                    buf.append(data);
                    if (last) {
                        String text = buf.toString();
                        buf.setLength(0);
                        dispatch(text);
                    }
                }
                return null;
            }

            private void dispatch(String text) {
                try {
                    JsonNode node = objectMapper.readTree(text);
                    int id = node.path("id").asInt(-1);
                    if (id > 0) {
                        CompletableFuture<JsonNode> f = pending.remove(id);
                        if (f != null) {
                            if (node.has("error")) {
                                f.completeExceptionally(new RuntimeException("CDP: " + node.get("error")));
                            } else {
                                f.complete(node.path("result"));
                            }
                        }
                    }
                    String method = node.path("method").asText("");
                    if (!method.isBlank()) {
                        switch (method) {
                            case "Network.requestWillBeSent" -> inflightRequests.incrementAndGet();
                            case "Network.loadingFinished", "Network.loadingFailed",
                                 "Network.webSocketClosed" -> {
                                // webSocketClosed: WS не генерирует loadingFinished — декрементируем
                                // здесь, чтобы не блокировать waitForNetworkIdle.
                                if (inflightRequests.get() > 0) {
                                    inflightRequests.decrementAndGet();
                                }
                            }
                            case "Fetch.requestPaused" -> handleFetchRequestPaused(node);
                            default -> {
                                // прочие события игнорируем
                            }
                        }
                        CompletableFuture<Void> w = waiters.remove(method);
                        if (w != null) {
                            w.complete(null);
                        }
                    }
                } catch (Exception e) {
                    log.debug("CDP dispatch error: {}", e.getMessage());
                }
            }

            private void handleFetchRequestPaused(JsonNode node) {
                JsonNode params = node.path("params");
                String requestId = params.path("requestId").asText("");
                String requestUrl = params.path("request").path("url").asText("");
                try {
                    URI uri = new URI(requestUrl);
                    String scheme = uri.getScheme();
                    // data:, blob:, about: — браузерные схемы без сетевого запроса, пропускаем
                    if (scheme != null && !scheme.equals("http") && !scheme.equals("https")) {
                        cdpContinue(requestId);
                        return;
                    }
                    // browser-level сессия — без фильтрации
                    if (allowedDomain == null) {
                        cdpContinue(requestId);
                        return;
                    }
                    String host = uri.getHost();
                    String hostLower = host == null ? "" : host.toLowerCase(Locale.ROOT);
                    boolean allowed = hostLower.equals(allowedDomain)
                            || hostLower.endsWith("." + allowedDomain)
                            || isHostAllowedBySet(hostLower, allowedThirdPartyHosts);
                    if (!allowed) {
                        if (blockedHostsLogged.add(hostLower)) {
                            log.debug("CDP blocked third-party host={} url={}", host, requestUrl);
                        }
                        cdpFail(requestId, "AccessDenied");
                        return;
                    }
                    // SSRF trust boundary (§5.4): даже разрешённый по имени хост обязан резолвиться
                    // в публичный IP. Без этого Chromium внутри headless-браузера может сходить на
                    // приватный/loopback адрес (DNS-rebinding, allowlist на внутренний хост) — этого
                    // не ловит static UrlValidator на границе SiteCrawler. Резолвим тем же валидатором.
                    if (!hostSafetyCache.computeIfAbsent(hostLower, urlValidator::isHostSafe)) {
                        if (blockedHostsLogged.add(hostLower)) {
                            log.warn("CDP blocked host resolving to private/blocked IP host={} url={}",
                                    host, requestUrl);
                        }
                        cdpFail(requestId, "AccessDenied");
                        return;
                    }
                    cdpContinue(requestId);
                } catch (URISyntaxException e) {
                    cdpFail(requestId, "AddressUnreachable");
                }
            }

            private void cdpContinue(String requestId) {
                String p = objectMapper.createObjectNode().put("requestId", requestId).toString();
                sendAsync("{\"id\":" + ids.incrementAndGet()
                        + ",\"method\":\"Fetch.continueRequest\",\"params\":" + p + "}");
            }

            private void cdpFail(String requestId, String errorReason) {
                String p = objectMapper.createObjectNode()
                        .put("requestId", requestId).put("errorReason", errorReason).toString();
                sendAsync("{\"id\":" + ids.incrementAndGet()
                        + ",\"method\":\"Fetch.failRequest\",\"params\":" + p + "}");
            }

            @Override
            public void onError(WebSocket ws, Throwable err) {
                log.debug("CDP WebSocket error: {}", err.getMessage());
                pending.values().forEach(f -> f.completeExceptionally(err));
                waiters.values().forEach(f -> f.completeExceptionally(err));
            }
        }
    }

    private static Set<String> normalizeHostSet(Set<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String h : hosts) {
            if (h == null) {
                continue;
            }
            String v = h.trim().toLowerCase(Locale.ROOT);
            if (!v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    private static boolean isHostAllowedBySet(String host, Set<String> allowedHosts) {
        if (host == null || host.isBlank() || allowedHosts == null || allowedHosts.isEmpty()) {
            return false;
        }
        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Проекция отрендеренного DOM → контрактный {@link PageAnalysisResult} (DYNAMIC). Делегируем
     * {@link PageExtractor}, чтобы DYNAMIC и STATIC давали единый набор полей для движка правил
     * (title берётся из {@code doc.title()} отрендеренного DOM).
     */
    private PageAnalysisResult buildResult(String url, String html) {
        Document doc = Jsoup.parse(html, url);
        return PageExtractor.extract(url, doc, extractDomain(url), RenderMode.DYNAMIC);
    }

    private static String extractDomain(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
