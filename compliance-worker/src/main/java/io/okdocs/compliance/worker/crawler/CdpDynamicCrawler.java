package io.okdocs.compliance.worker.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.enums.RenderMode;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.MDC;
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
import java.util.ArrayDeque;
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

    /** UA для headless-прохода: настроенный базовый + суффикс, чтобы отличать DYNAMIC от STATIC в логах сайта. */
    private final String userAgent;
    private final String chromiumBaseUrl;
    private final String authHeader;
    private final int pageTimeoutMs;
    private final int concurrency;
    private final int batchTimeoutSeconds;
    private final long availabilityRecheckIntervalMs;
    private final int networkIdleQuietMs;
    private final int networkIdleTimeoutMs;
    private final boolean preConsentTrackingEnabled;
    private final boolean consentScenariosEnabled;
    private final int consentWaitAfterClickMs;
    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final UrlValidator urlValidator;
    private final AtomicBoolean available = new AtomicBoolean(false);
    private final AtomicLong lastAvailabilityCheckMs = new AtomicLong(0);

    /**
     * Инжектируемый помощник consent-сценариев (Фаза 4). Экспонирует {@code __okdocksConsent.inspect()}
     * (структура баннера → JSON) и {@code __okdocksConsent.click(action)} (клик accept|reject|manage).
     * <p>
     * ВАЖНО: {@link CdpSession#eval(String)} экранирует {@code \\} и {@code "}, поэтому здесь — только
     * одинарные кавычки и БЕЗ обратных слешей (никаких regex со спецсимволами). Сопоставление кнопок —
     * по подстрокам в нижнем регистре на нескольких языках ЕС (en/de/fr/es/it). Баннер опознаётся по
     * id/class/role контейнера или CMP-сигнатуре; кнопки — по тексту/aria-label.
     */
    private static final String CONSENT_JS =
            "window.__okdocksConsent=(function(){"
            + "var ACCEPT=['accept all','accept cookies','accept','agree','allow all','allow cookies',"
            + "'i agree','got it','ok','alle akzeptieren','akzeptieren','zustimmen','einverstanden',"
            + "'tout accepter','accepter','jaccepte','aceptar todo','aceptar','acconsenti','accetta'];"
            + "var REJECT=['reject all','reject','decline','deny','refuse','disagree','only necessary',"
            + "'necessary only','essential only','do not accept','alle ablehnen','ablehnen','nur notwendige',"
            + "'tout refuser','refuser','continuer sans accepter','rechazar todo','rechazar','solo necesarias',"
            + "'rifiuta tutto','rifiuta'];"
            + "var MANAGE=['manage','preferences','settings','customize','customise','options','more options',"
            + "'einstellungen','verwalten','anpassen','personnaliser','parametres','gerer','configurar',"
            + "'preferencias','personalizar','gestisci','impostazioni','personalizza'];"
            + "var SAVE=['save preferences','save settings','save','confirm choices','confirm my choices',"
            + "'auswahl speichern','speichern','enregistrer','guardar','salva'];"
            + "var CMP={'#onetrust-banner-sdk':'OneTrust','#CybotCookiebotDialog':'Cookiebot',"
            + "'#usercentrics-root':'Usercentrics','#cookiescript_injected':'CookieScript',"
            + "'.cc-window':'CookieConsent','#cookie-law-info-bar':'CookieLawInfo',"
            + "'#didomi-host':'Didomi','#truste-consent-track':'TrustArc','#sp_message_container_':'Sourcepoint',"
            + "'#axeptio_overlay':'Axeptio','#tarteaucitronRoot':'tarteaucitron'};"
            + "function low(s){return (s||'').toLowerCase();}"
            + "function txt(el){return low((el.textContent||'')+' '+(el.getAttribute&&el.getAttribute('aria-label')||''));}"
            + "function visible(el){if(!el)return false;var r=el.getBoundingClientRect();"
            + "if(r.width<1||r.height<1)return false;var st=getComputedStyle(el);"
            + "return st.display!=='none'&&st.visibility!=='hidden'&&st.opacity!=='0';}"
            + "function roots(){var out=[],seen=[];function walk(r){if(!r||seen.indexOf(r)>=0)return;"
            + "seen.push(r);out.push(r);var all=[];try{all=r.querySelectorAll('*');}catch(e){}"
            + "for(var i=0;i<all.length;i++){if(all[i].shadowRoot)walk(all[i].shadowRoot);"
            + "if(low(all[i].tagName)==='iframe'){try{walk(all[i].contentDocument);}catch(e){}}}}walk(document);return out;}"
            + "function queryAll(sel){var rs=roots(),out=[];for(var i=0;i<rs.length;i++){try{"
            + "var a=rs[i].querySelectorAll(sel);for(var j=0;j<a.length;j++)out.push(a[j]);}catch(e){}}return out;}"
            + "function clickable(){return queryAll('button,a[role=button],a,input[type=button],input[type=submit],[role=button]');}"
            + "function findCmp(){for(var sel in CMP){try{var els=queryAll(sel);"
            + "for(var x=0;x<els.length;x++){if(visible(els[x]))return {el:els[x],name:CMP[sel]};}}catch(e){}}"
            + "for(var sel2 in CMP){try{if(sel2.charAt(sel2.length-1)==='_'){"
            + "var all=queryAll('[id^='+JSON.stringify(sel2.slice(1))+']');"
            + "for(var i=0;i<all.length;i++){if(visible(all[i]))return {el:all[i],name:CMP[sel2]};}}}catch(e){}}"
            + "return null;}"
            + "function findBanner(){var c=findCmp();if(c)return c;"
            + "var cand=queryAll('[id*=cookie],[class*=cookie],[id*=consent],[class*=consent],"
            + "[aria-label*=cookie],[aria-label*=consent],[role=dialog],[role=alertdialog]');"
            + "for(var i=0;i<cand.length;i++){var el=cand[i];if(!visible(el))continue;"
            + "var t=txt(el);if(t.indexOf('cookie')>=0||t.indexOf('consent')>=0||t.indexOf('privacy')>=0||"
            + "t.indexOf('datenschutz')>=0||t.indexOf('confidentialit')>=0)return {el:el,name:null};}return null;}"
            + "function matchBtn(scope,words){var btns=clickable();for(var i=0;i<btns.length;i++){"
            + "var b=btns[i];if(!visible(b))continue;if(scope&&!scope.contains(b))continue;"
            + "var t=txt(b).trim();for(var j=0;j<words.length;j++){if(t.indexOf(words[j])>=0)return b;}}return null;}"
            + "function prechecked(scope){var root=scope||document;"
            + "var boxes=root.querySelectorAll('input[type=checkbox],[role=switch],[aria-checked]');"
            + "for(var i=0;i<boxes.length;i++){var b=boxes[i];if(!visible(b))continue;"
            + "var lbl=txt(b)+' '+low((b.name||'')+' '+(b.id||''));"
            + "if(lbl.indexOf('necess')>=0||lbl.indexOf('essential')>=0||lbl.indexOf('required')>=0)continue;"
            + "if(b.checked===true||b.getAttribute('aria-checked')==='true')return true;}return false;}"
            + "function inspect(){var bn=findBanner();if(!bn)return JSON.stringify({bannerFound:false});"
            + "var scope=bn.el;var acc=matchBtn(scope,ACCEPT);var rej=matchBtn(scope,REJECT);"
            + "var man=matchBtn(scope,MANAGE);var sav=matchBtn(scope,SAVE);"
            + "var same=!!(acc&&rej&&acc.parentNode===rej.parentNode);"
            + "return JSON.stringify({bannerFound:true,acceptButtonFound:!!acc,rejectButtonFound:!!rej,"
            + "manageButtonFound:!!man,savePreferencesFound:!!sav,rejectSameLevelAsAccept:same,"
            + "precheckedToggles:prechecked(scope),cmpProvider:bn.name});}"
            + "function click(action){var bn=findBanner();if(!bn)return false;var scope=bn.el;"
            + "var words=action==='reject'?REJECT:(action==='manage'?MANAGE:ACCEPT);"
            + "var b=matchBtn(scope,words);if(!b)return false;try{b.click();return true;}catch(e){return false;}}"
            + "return {inspect:inspect,click:click};})()";

    public CdpDynamicCrawler(ComplianceWorkerProperties properties, ObjectMapper objectMapper,
                             UrlValidator urlValidator) {
        var dyn = properties.getCrawler().getDynamic();
        this.userAgent = properties.getCrawler().getUserAgent() + " CDP/headless";
        this.chromiumBaseUrl = dyn.getBaseUrl();
        this.authHeader = "Bearer " + dyn.getAuthToken();
        this.pageTimeoutMs = dyn.getPageTimeoutMs();
        this.concurrency = Math.max(1, dyn.getConcurrency());
        this.batchTimeoutSeconds = dyn.getBatchTimeoutSeconds();
        this.availabilityRecheckIntervalMs = dyn.getAvailabilityRecheckInterval().toMillis();
        this.networkIdleQuietMs = dyn.getNetworkIdle().getQuietMs();
        this.networkIdleTimeoutMs = dyn.getNetworkIdle().getTimeoutMs();
        this.preConsentTrackingEnabled = dyn.getPreConsentTracking().isEnabled();
        this.consentScenariosEnabled = dyn.getConsentScenarios().isEnabled();
        this.consentWaitAfterClickMs = dyn.getConsentScenarios().getWaitAfterClickMs();
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
        if (!force && (now - last) < availabilityRecheckIntervalMs) {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorMessage = e.getMessage();
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

        // ОДНА browser-level CdpSession на весь batch. Браузерный BrowserContext привязан к
        // lifecycle той сессии, через которую создан (Browserless): если закрыть сокет сразу после
        // Target.createBrowserContext, контекст исчезает, и Target.createTarget из новой сессии падает
        // «Failed to find browser context». Поэтому держим browser-сессию открытой, через неё же
        // создаём/закрываем targets и dispose'им контекст. getBrowserWsUrl() — один раз.
        CdpSession browser;
        try {
            browser = new CdpSession(getBrowserWsUrl(), authHeader);
        } catch (Exception e) {
            throw new RuntimeException("Failed to open CDP browser session: " + chromiumBaseUrl, e);
        }
        try {
            String browserContextId = createBrowserContext(browser);
            try {
                return crawlWithContext(browser, normalizedUrls, allowedThirdPartyHosts, browserContextId, t0);
            } finally {
                disposeBrowserContext(browser, browserContextId);
            }
        } finally {
            browser.close();
        }
    }

    private String createBrowserContext(CdpSession browser) {
        try {
            JsonNode result = browser.send("Target.createBrowserContext", null);
            return result.path("browserContextId").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create BrowserContext", e);
        }
    }

    private void disposeBrowserContext(CdpSession browser, String browserContextId) {
        if (browserContextId == null || browserContextId.isBlank()) {
            return;
        }
        try {
            browser.send("Target.disposeBrowserContext",
                    objectMapper.createObjectNode()
                            .put("browserContextId", browserContextId).toString());
        } catch (Exception e) {
            log.debug("disposeBrowserContext ctx={} failed: {}", browserContextId, e.getMessage());
        }
    }

    private Map<String, PageAnalysisResult> crawlWithContext(
            CdpSession browser,
            List<String> urls,
            Set<String> allowedThirdPartyHosts,
            String browserContextId,
            long t0) {

        BlockingQueue<String> queue = new LinkedBlockingQueue<>(urls);
        ConcurrentHashMap<String, PageAnalysisResult> results = new ConcurrentHashMap<>();
        // Единый флаг отмены: воркеры проверяют его на каждой итерации и после InterruptedException —
        // так deadline гарантированно жёсткий.
        AtomicBoolean cancelled = new AtomicBoolean(false);

        // MDC (scanId/scanKind) живёт на потоке Kafka-листенера; cdp-worker-* — отдельные потоки пула,
        // в них MDC пуст. Снимаем контекст вызывающего потока и ставим его в каждом воркере, чтобы
        // CDP-логи трассировались по scanId, как и остальной пайплайн.
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();

        int workers = Math.min(concurrency, urls.size());
        ExecutorService pool = Executors.newFixedThreadPool(workers,
                r -> new Thread(r, "cdp-worker-" + System.nanoTime() % 1000));
        try {
            List<Future<?>> futures = new ArrayList<>(workers);
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() ->
                        runWorker(browser, queue, results, allowedThirdPartyHosts, browserContextId,
                                cancelled, parentMdc)));
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
    private void runWorker(CdpSession browser,
                           BlockingQueue<String> queue,
                           ConcurrentHashMap<String, PageAnalysisResult> results,
                           Set<String> allowedThirdPartyHosts,
                           String browserContextId,
                           AtomicBoolean cancelled,
                           Map<String, String> parentMdc) {
        if (parentMdc != null) {
            MDC.setContextMap(parentMdc);
        }
        String targetId = null;
        try {
            String[] target = createTargetInContext(browser, browserContextId);
            targetId = target[0];
            String wsUrl = target[1];

            try (CdpSession s = new CdpSession(wsUrl, authHeader)) {
                initializePageSession(s);

                String url;
                while ((url = queue.poll()) != null) {
                    if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    long pageStart = System.currentTimeMillis();
                    try {
                        PageFetch result = fetchPage(browser, s, url, allowedThirdPartyHosts);
                        long ms = System.currentTimeMillis() - pageStart;
                        log.info("CDP crawl ok url={} finalUrl={} html-len={} preConsent={} ms={}",
                                url, result.finalUrl(), result.html().length(),
                                result.preConsentHosts().size(), ms);
                        results.put(url, buildResult(result));
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
                closeTarget(browser, targetId);
            }
            MDC.clear();
        }
    }

    private void initializePageSession(CdpSession s) throws Exception {
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
    }

    /**
     * Создаёт Target внутри BrowserContext через общую browser-сессию (НЕ открывает новую — иначе
     * контекст из другой сессии не виден). Возвращает [targetId, wsDebuggerUrl].
     */
    private String[] createTargetInContext(CdpSession browser, String browserContextId) throws Exception {
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

    private void closeTarget(CdpSession browser, String targetId) {
        try {
            browser.send("Target.closeTarget",
                    objectMapper.createObjectNode().put("targetId", targetId).toString());
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

    /**
     * Результат одного fetch: финальные url/title/html + наблюдения до согласия — сторонние хосты,
     * cookies и ключи localStorage.
     */
    private record PageFetch(String finalUrl, String title, String html, List<String> preConsentHosts,
                             List<io.okdocs.compliance.contracts.crawler.ObservedCookie> preConsentCookies,
                             List<String> preConsentStorageKeys,
                             boolean preConsentCookiesSnapshotAvailable,
                             boolean preConsentStorageSnapshotAvailable,
                             io.okdocs.compliance.contracts.crawler.ConsentScenarioResult consentScenario) {
    }

    private record CookieSnapshot(List<io.okdocs.compliance.contracts.crawler.ObservedCookie> cookies,
                                  boolean available) {
    }

    private record RequestObservation(long sequence, String host, double epochMs, String resourceType) {
    }

    private record ConsentClickBoundary(long sequence, double epochMs, boolean clicked) {
    }

    private record StorageSnapshot(List<String> keys, boolean available) {
    }

    private PageFetch fetchPage(CdpSession browser, CdpSession s, String targetUrl,
                                Set<String> allowedThirdPartyHosts) throws Exception {
        String allowedDomain = extractDomain(targetUrl);
        s.setDomainPolicy(allowedDomain, allowedThirdPartyHosts);
        try {
            // Наблюдатель момента баннера ставим ДО навигации, чтобы он исполнился раньше скриптов
            // страницы (addScriptToEvaluateOnNewDocument) и поймал даже синхронно вставленный баннер.
            boolean bannerObserverInstalled = !preConsentTrackingEnabled || installBannerObserver(s);

            JsonNode navigation = s.send("Page.navigate",
                    objectMapper.createObjectNode().put("url", targetUrl).toString());
            String navigationError = navigation.path("errorText").asText("");
            if (!navigationError.isBlank()) {
                throw new IllegalStateException("CDP navigation failed: " + navigationError);
            }
            s.waitForEvent("Page.loadEventFired", pageTimeoutMs);

            // Быстрый снимок динамики: ждём короткую "тишину", чтобы не блокироваться на long-polling.
            s.waitForNetworkIdle(networkIdleQuietMs, networkIdleTimeoutMs);

            try {
                s.send("Page.stopLoading", null);
            } catch (Exception ignored) {
                // best-effort
            }

            // Один round-trip: собираем url+title+html+момент баннера+ключи localStorage одним JS-вызовом.
            // localStorage снимаем здесь (до взаимодействия с баннером) — это состояние «до согласия».
            String json = s.eval(
                    "(function(){var b=window.__okdocksBannerTs;var ls=[];"
                            + "try{for(var i=0;i<localStorage.length;i++){ls.push(localStorage.key(i));}}catch(e){}"
                            + "var r={u:window.location.href,"
                            + "t:document.title,h:document.documentElement.outerHTML,"
                            + "b:(typeof b==='number'?b:null),"
                            + "o:(window.__okdocksBannerObs===1),ls:ls};return JSON.stringify(r);})()");
            JsonNode node = objectMapper.readTree(json);
            String finalUrl = node.path("u").asText("");
            if (isBrowserInternalUrl(finalUrl)) {
                throw new IllegalStateException("CDP navigation ended on browser internal page: " + finalUrl);
            }

            List<String> preConsentHosts = List.of();
            boolean bannerObserverActive = bannerObserverInstalled && node.path("o").asBoolean(false);
            if (preConsentTrackingEnabled && bannerObserverActive) {
                Double bannerTs = node.path("b").isNumber() ? node.path("b").asDouble() : null;
                preConsentHosts = computePreConsentHosts(
                        s.firstRequestEpochMsByHost(), bannerTs, allowedDomain);
            }

            // Cookies/storage до согласия (Этап 4 Phase 1): текущий проход НЕ кликает по баннеру,
            // поэтому снимок = состояние до согласия. Атрибуты secure/httpOnly доступны только через
            // CDP Network.getCookies (не из document.cookie). Ключи localStorage пришли в node.ls.
            CookieSnapshot cookieSnapshot = collectCookies(s);
            List<String> preConsentStorageKeys = readStringArray(node.path("ls"));

            // Consent-сценарии (Фаза 4): после снимка «до согласия» кликаем Reject, затем Accept,
            // фиксируя cookies/трекеры после каждого действия. Best-effort: любой сбой → notEvaluated,
            // скан не падает. Снимок «до» — это hosts/cookies выше; «после Reject» — ключевой вход
            // для EU/UK consent-правил («трекеры пережили отказ»).
            io.okdocs.compliance.contracts.crawler.ConsentScenarioResult consentScenario =
                    io.okdocs.compliance.contracts.crawler.ConsentScenarioResult.disabled();
            if (consentScenariosEnabled) {
                consentScenario = driveConsentScenarioIsolated(
                        browser, targetUrl, allowedDomain, allowedThirdPartyHosts);
            }

            return new PageFetch(
                    finalUrl,
                    node.path("t").asText(""),
                    node.path("h").asText(""),
                    preConsentHosts,
                    cookieSnapshot.cookies(),
                    preConsentStorageKeys,
                    cookieSnapshot.available(),
                    node.has("ls") && node.path("ls").isArray(),
                    consentScenario);
        } finally {
            s.clearDomainPolicy();
        }
    }

    /**
     * Cookies браузера на момент снимка (до согласия) через CDP {@code Network.getCookies}. Атрибуты
     * secure/httpOnly/sameSite доступны только так (не из {@code document.cookie}). {@code session} —
     * cookie без срока истечения (CDP {@code session:true} или {@code expires <= 0}). Любой сбой →
     * пустой список (cookie-правила тогда не сработают, скан не падает).
     */
    private CookieSnapshot collectCookies(CdpSession s) {
        try {
            JsonNode resp = s.send("Network.getCookies", null);
            JsonNode cookies = resp.path("cookies");
            if (!cookies.isArray() || cookies.isEmpty()) {
                return new CookieSnapshot(List.of(), true);
            }
            List<io.okdocs.compliance.contracts.crawler.ObservedCookie> result = new ArrayList<>(cookies.size());
            for (JsonNode c : cookies) {
                boolean session = c.path("session").asBoolean(false) || c.path("expires").asDouble(-1) <= 0;
                result.add(new io.okdocs.compliance.contracts.crawler.ObservedCookie(
                        c.path("name").asText(""),
                        c.path("domain").asText(""),
                        c.path("secure").asBoolean(false),
                        c.path("httpOnly").asBoolean(false),
                        c.path("sameSite").asText(null),
                        session));
            }
            return new CookieSnapshot(result, true);
        } catch (Exception e) {
            log.debug("CDP Network.getCookies failed: {}", e.getMessage());
            return new CookieSnapshot(List.of(), false);
        }
    }

    private StorageSnapshot collectStorageKeys(CdpSession s) {
        try {
            String json = s.eval("(function(){var out=[];try{for(var i=0;i<localStorage.length;i++){"
                    + "out.push('local:'+localStorage.key(i));}}catch(e){return JSON.stringify({ok:false,keys:[]});}"
                    + "try{for(var j=0;j<sessionStorage.length;j++){out.push('session:'+sessionStorage.key(j));}}"
                    + "catch(e){return JSON.stringify({ok:false,keys:[]});}"
                    + "return JSON.stringify({ok:true,keys:out});})()");
            JsonNode node = objectMapper.readTree(json);
            return new StorageSnapshot(readStringArray(node.path("keys")), node.path("ok").asBoolean(false));
        } catch (Exception e) {
            log.debug("Post-reject Web Storage snapshot failed: {}", e.getMessage());
            return new StorageSnapshot(List.of(), false);
        }
    }

    /** Запускает Reject в отдельном чистом BrowserContext и всегда уничтожает его после снимка. */
    private io.okdocs.compliance.contracts.crawler.ConsentScenarioResult driveConsentScenarioIsolated(
            CdpSession browser,
            String targetUrl,
            String allowedDomain,
            Set<String> allowedThirdPartyHosts) {
        String contextId = null;
        String targetId = null;
        try {
            contextId = createBrowserContext(browser);
            String[] target = createTargetInContext(browser, contextId);
            targetId = target[0];
            try (CdpSession scenario = new CdpSession(target[1], authHeader)) {
                initializePageSession(scenario);
                scenario.setDomainPolicy(allowedDomain, allowedThirdPartyHosts);
                JsonNode navigation = scenario.send("Page.navigate",
                        objectMapper.createObjectNode().put("url", targetUrl).toString());
                String error = navigation.path("errorText").asText("");
                if (!error.isBlank()) {
                    throw new IllegalStateException("Consent navigation failed: " + error);
                }
                scenario.waitForEvent("Page.loadEventFired", pageTimeoutMs);
                scenario.waitForNetworkIdle(networkIdleQuietMs, networkIdleTimeoutMs);
                return driveConsentScenarios(scenario, allowedDomain);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return consentFailure(io.okdocs.compliance.contracts.crawler.ConsentBannerInfo.notFound(),
                    false, false, false,
                    io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason.TIMEOUT);
        } catch (Exception e) {
            log.debug("Isolated consent scenario failed url={}: {}", targetUrl, e.getMessage());
            return consentFailure(io.okdocs.compliance.contracts.crawler.ConsentBannerInfo.notFound(),
                    false, false, false,
                    io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason.CDP_ERROR);
        } finally {
            if (targetId != null) {
                closeTarget(browser, targetId);
            }
            if (contextId != null) {
                disposeBrowserContext(browser, contextId);
            }
        }
    }

    /**
     * Инспектирует баннер, ставит точную временную/sequence-границу перед Reject и снимает все
     * разрешённые запросы, cookies и Web Storage после клика. PASS допустим только если этот метод
     * вернул {@code postRejectSnapshotAvailable=true}.
     */
    private io.okdocs.compliance.contracts.crawler.ConsentScenarioResult driveConsentScenarios(
            CdpSession s, String allowedDomain) {
        try {
            io.okdocs.compliance.contracts.crawler.ConsentBannerInfo banner = inspectBannerWithWait(s);
            if (!banner.bannerFound()) {
                return consentFailure(banner, true, false, false,
                        io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason.BANNER_NOT_FOUND);
            }
            if (!banner.rejectButtonFound()) {
                return consentFailure(banner, true, false, false,
                        io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason.REJECT_NOT_FOUND);
            }

            ConsentClickBoundary boundary;
            try {
                boundary = clickRejectWithBoundary(s);
            } catch (Exception clickError) {
                log.debug("Consent Reject click failed: {}", clickError.getMessage());
                return consentFailure(banner, true, true, false,
                        io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason.REJECT_CLICK_FAILED);
            }
            if (!boundary.clicked()) {
                return consentFailure(banner, true, true, false,
                        io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason.REJECT_CLICK_FAILED);
            }
            waitAfterConsentClick(s);
            if (Thread.currentThread().isInterrupted()) {
                return consentFailure(banner, true, true, true,
                        io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason.TIMEOUT);
            }

            CookieSnapshot cookies = collectCookies(s);
            StorageSnapshot storage = collectStorageKeys(s);
            List<RequestObservation> observations = s.requestsAfter(
                    boundary.sequence(), boundary.epochMs());
            if (!cookies.available() || !storage.available()) {
                return consentFailure(banner, true, true, true,
                        io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason.POST_REJECT_CAPTURE_FAILED);
            }
            List<String> afterRejectHosts = thirdPartyHosts(observations, allowedDomain);
            List<io.okdocs.compliance.contracts.crawler.NetworkRequestObservation> requestEvidence =
                    observations.stream()
                            .map(o -> new io.okdocs.compliance.contracts.crawler.NetworkRequestObservation(
                                    o.sequence(), o.epochMs(), o.host(), o.resourceType()))
                            .toList();

            return new io.okdocs.compliance.contracts.crawler.ConsentScenarioResult(
                    banner, cookies.cookies(), afterRejectHosts, storage.keys(), List.of(),
                    requestEvidence, true, true, true, true,
                    io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason.NONE,
                    s.requestTimelineTruncated(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return consentFailure(io.okdocs.compliance.contracts.crawler.ConsentBannerInfo.notFound(),
                    false, false, false,
                    io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason.TIMEOUT);
        } catch (Exception e) {
            log.debug("Consent scenario run failed: {}", e.getMessage());
            return consentFailure(io.okdocs.compliance.contracts.crawler.ConsentBannerInfo.notFound(),
                    false, false, false,
                    io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason.CDP_ERROR);
        }
    }

    private static io.okdocs.compliance.contracts.crawler.ConsentScenarioResult consentFailure(
            io.okdocs.compliance.contracts.crawler.ConsentBannerInfo banner,
            boolean inspected,
            boolean rejectFound,
            boolean rejectClicked,
            io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason reason) {
        return io.okdocs.compliance.contracts.crawler.ConsentScenarioResult.failed(
                banner, inspected, rejectFound, rejectClicked, reason);
    }

    private io.okdocs.compliance.contracts.crawler.ConsentBannerInfo inspectBannerWithWait(CdpSession s)
            throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(500, consentWaitAfterClickMs);
        io.okdocs.compliance.contracts.crawler.ConsentBannerInfo banner;
        do {
            banner = inspectBanner(s);
            if (banner.bannerFound()) {
                return banner;
            }
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted());
        return banner;
    }

    /** Инспектирует структуру cookie-баннера/CMP через JS, парсит в {@link ConsentBannerInfo}. */
    private io.okdocs.compliance.contracts.crawler.ConsentBannerInfo inspectBanner(CdpSession s)
            throws Exception {
        String json = s.eval(CONSENT_JS + ";__okdocksConsent.inspect()");
        if (json == null || json.isBlank()) {
            return io.okdocs.compliance.contracts.crawler.ConsentBannerInfo.notFound();
        }
        return parseBannerInfo(objectMapper.readTree(json));
    }

    /**
     * Парсит JSON структуры баннера от {@code __okdocksConsent.inspect()} в {@link ConsentBannerInfo}.
     * Package-private и статический — чтобы тестировать маппинг без живого CDP.
     */
    static io.okdocs.compliance.contracts.crawler.ConsentBannerInfo parseBannerInfo(JsonNode n) {
        if (n == null || !n.path("bannerFound").asBoolean(false)) {
            return io.okdocs.compliance.contracts.crawler.ConsentBannerInfo.notFound();
        }
        String cmp = n.path("cmpProvider").isNull() ? null : n.path("cmpProvider").asText(null);
        return new io.okdocs.compliance.contracts.crawler.ConsentBannerInfo(
                true,
                n.path("acceptButtonFound").asBoolean(false),
                n.path("rejectButtonFound").asBoolean(false),
                n.path("manageButtonFound").asBoolean(false),
                n.path("savePreferencesFound").asBoolean(false),
                n.path("rejectSameLevelAsAccept").asBoolean(false),
                n.path("precheckedToggles").asBoolean(false),
                (cmp == null || cmp.isBlank()) ? null : cmp);
    }

    /** Sequence снимается до Runtime.evaluate, а browser timestamp — непосредственно перед click(). */
    private ConsentClickBoundary clickRejectWithBoundary(CdpSession s) throws Exception {
        long sequence = s.currentRequestSequence();
        String json = s.eval(CONSENT_JS
                + ";(function(){var t=Date.now();var ok=__okdocksConsent.click('reject');"
                + "return JSON.stringify({epochMs:t,clicked:ok});})()");
        JsonNode result = objectMapper.readTree(json);
        return new ConsentClickBoundary(sequence, result.path("epochMs").asDouble(0),
                result.path("clicked").asBoolean(false));
    }

    /** Пауза на догрузку после клика по баннеру: сетевая тишина в пределах настроенного окна. */
    private void waitAfterConsentClick(CdpSession s) {
        long started = System.currentTimeMillis();
        s.waitForNetworkIdle(networkIdleQuietMs, consentWaitAfterClickMs);
        long remaining = consentWaitAfterClickMs - (System.currentTimeMillis() - started);
        while (remaining > 0 && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(Math.min(remaining, 100));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining = consentWaitAfterClickMs - (System.currentTimeMillis() - started);
        }
    }

    /** Уникальные сторонние хосты из полного потока после Reject, включая повторно вызванные. */
    private static List<String> thirdPartyHosts(List<RequestObservation> observations, String allowedDomain) {
        Set<String> result = new LinkedHashSet<>();
        for (RequestObservation observation : observations) {
            String host = observation.host();
            if (host == null) {
                continue;
            }
            if (allowedDomain != null && (host.equals(allowedDomain) || host.endsWith("." + allowedDomain))) {
                continue; // first-party хост — не сторонний трекер
            }
            result.add(host);
        }
        return List.copyOf(result);
    }

    /** Преобразует JSON-массив строк в List (для ключей localStorage). */
    private static List<String> readStringArray(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(array.size());
        for (JsonNode n : array) {
            String v = n.asText(null);
            if (v != null && !v.isBlank()) {
                result.add(v);
            }
        }
        return result;
    }

    /**
     * Инжектит в новый документ наблюдатель момента появления cookie-баннера: фиксирует
     * {@code window.__okdocksBannerTs = Date.now()} (epoch мс — та же шкала, что {@code wallTime}
     * запросов) при первом DOM-узле, чей текст/класс матчит маркеры баннера. Маркеры — зеркало
     * {@code PageExtractor.COOKIE_FLAG_PATTERN}, чтобы детект баннера был согласован со static-слоем.
     */
    private boolean installBannerObserver(CdpSession s) {
        try {
            String script = "(function(){"
                    + "if(window.__okdocksBannerObs)return;window.__okdocksBannerObs=1;"
                    + "window.__okdocksBannerTs=null;"
                    + "var re=/(cookie[-_ ]?consent|cookie[-_ ]?banner|cookie[-_ ]?notice|"
                    + "cookie[-_ ]?bar|cookiebot|cookiehub|cookiepro|cc-window|"
                    + "\\u0438\\u0441\\u043f\\u043e\\u043b\\u044c\\u0437\\u0443[\\u0435\\u0451]\\u0442 cookie|"
                    + "\\u0444\\u0430\\u0439\\u043b[\\u044b\\u0438] cookie)/i;"
                    + "function hit(el){if(!el||window.__okdocksBannerTs)return;"
                    + "var s=((el.className&&el.className.toString?el.className.toString():'')+' '+"
                    + "(el.id||'')+' '+(el.textContent||'')).slice(0,4000);"
                    + "if(re.test(s)){window.__okdocksBannerTs=Date.now();}}"
                    + "var mo=new MutationObserver(function(muts){"
                    + "for(var i=0;i<muts.length;i++){var a=muts[i].addedNodes;"
                    + "for(var j=0;j<a.length;j++){if(a[j].nodeType===1)hit(a[j]);}}});"
                    + "function start(){try{mo.observe(document.documentElement||document,"
                    + "{childList:true,subtree:true});}catch(e){}"
                    + "if(document.body)hit(document.body);}"
                    + "if(document.documentElement)start();"
                    + "else document.addEventListener('DOMContentLoaded',start);"
                    + "})();";
            s.send("Page.addScriptToEvaluateOnNewDocument",
                    objectMapper.createObjectNode().put("source", script).toString());
            return true;
        } catch (Exception e) {
            // Инжект не критичен: без него не подтверждаем порядок загрузки и отдаём пустой
            // preConsentHosts, чтобы не получить ложный CONFIRMED.
            log.debug("CDP banner observer install failed: {}", e.getMessage());
            return false;
        }
    }

    // ── CdpSession (WebSocket) ────────────────────────────────────────────────

    private final class CdpSession implements AutoCloseable {
        private static final int MAX_REQUEST_TIMELINE_SIZE = 5_000;
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
        // Таймлайн «трекер до согласия» (§3.2): хост → минимальный wallTime его запроса (epoch мс).
        // Заполняется только для Fetch-запросов, которые crawler реально продолжил; обнуляется
        // per-page в setDomainPolicy.
        private final ConcurrentHashMap<String, Double> firstRequestEpochMsByHost = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, RequestObservation> requestByNetworkId = new ConcurrentHashMap<>();
        private final Set<String> continuedNetworkIds = ConcurrentHashMap.newKeySet();
        private final Set<String> failedNetworkIds = ConcurrentHashMap.newKeySet();
        private final Set<String> committedNetworkIds = ConcurrentHashMap.newKeySet();
        private final ArrayDeque<RequestObservation> requestTimeline = new ArrayDeque<>();
        private final AtomicLong requestSequence = new AtomicLong(0);
        private volatile boolean requestTimelineTruncated;
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
            this.firstRequestEpochMsByHost.clear();
            this.requestByNetworkId.clear();
            this.continuedNetworkIds.clear();
            this.failedNetworkIds.clear();
            this.committedNetworkIds.clear();
            synchronized (requestTimeline) {
                this.requestTimeline.clear();
            }
            this.requestSequence.set(0);
            this.requestTimelineTruncated = false;
        }

        void clearDomainPolicy() {
            this.allowedDomain = null;
            this.allowedThirdPartyHosts = Set.of();
            this.blockedHostsLogged.clear();
            this.hostSafetyCache.clear();
        }

        /** Снимок таймлайна запросов текущей страницы (хост → минимальный wallTime, epoch мс). */
        Map<String, Double> firstRequestEpochMsByHost() {
            return new LinkedHashMap<>(firstRequestEpochMsByHost);
        }

        long currentRequestSequence() {
            return requestSequence.get();
        }

        boolean requestTimelineTruncated() {
            return requestTimelineTruncated;
        }

        List<RequestObservation> requestsAfter(long boundarySequence, double boundaryEpochMs) {
            synchronized (requestTimeline) {
                return requestTimeline.stream()
                        .filter(o -> isAfterRejectBoundary(
                                o.sequence(), o.epochMs(), boundarySequence, boundaryEpochMs))
                        .toList();
            }
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
                            case "Network.requestWillBeSent" -> {
                                inflightRequests.incrementAndGet();
                                recordRequestTimeline(node.path("params"));
                            }
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

            /**
             * Запоминает момент запроса по {@code wallTime} (Unix-epoch секунды из CDP) → epoch мс.
             * Берём время именно из {@code requestWillBeSent}, а НЕ {@code Fetch.requestPaused}: там
             * время искажено нашей паузой/SSRF-фильтром. В итоговый таймлайн хост попадёт только
             * после {@code Fetch.continueRequest}; заблокированные crawler'ом запросы не считаются
             * реально ушедшими.
             */
            private void recordRequestTimeline(JsonNode params) {
                String networkId = params.path("requestId").asText("");
                if (networkId.isBlank() || failedNetworkIds.contains(networkId)) {
                    return;
                }
                double wallTime = params.path("wallTime").asDouble(0.0);
                if (wallTime <= 0.0) {
                    return; // без надёжной шкалы времени запрос в таймлайн не кладём
                }
                String requestUrl = params.path("request").path("url").asText("");
                try {
                    URI uri = new URI(requestUrl);
                    String scheme = uri.getScheme();
                    if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                        return;
                    }
                    String host = uri.getHost();
                    if (host == null || host.isBlank()) {
                        return;
                    }
                    double epochMs = wallTime * 1000.0;
                    RequestObservation observation = new RequestObservation(
                            0, host.toLowerCase(Locale.ROOT), epochMs,
                            params.path("type").asText("Other"));
                    requestByNetworkId.put(networkId, observation);
                    if (continuedNetworkIds.contains(networkId)) {
                        commitContinuedRequest(networkId, observation);
                    }
                } catch (URISyntaxException e) {
                    // нераспарсенный URL в таймлайн не попадает
                }
            }

            private void markRequestContinued(String networkId) {
                if (networkId == null || networkId.isBlank() || failedNetworkIds.contains(networkId)) {
                    return;
                }
                continuedNetworkIds.add(networkId);
                RequestObservation observation = requestByNetworkId.get(networkId);
                if (observation != null) {
                    commitContinuedRequest(networkId, observation);
                }
            }

            private void markRequestFailed(String networkId) {
                if (networkId == null || networkId.isBlank()) {
                    return;
                }
                failedNetworkIds.add(networkId);
                continuedNetworkIds.remove(networkId);
                requestByNetworkId.remove(networkId);
            }

            private void commitContinuedRequest(String networkId, RequestObservation observation) {
                if (!committedNetworkIds.add(networkId)) {
                    return;
                }
                firstRequestEpochMsByHost.merge(observation.host(), observation.epochMs(), Math::min);
                RequestObservation committed = new RequestObservation(
                        requestSequence.incrementAndGet(), observation.host(), observation.epochMs(),
                        observation.resourceType());
                synchronized (requestTimeline) {
                    if (requestTimeline.size() >= MAX_REQUEST_TIMELINE_SIZE) {
                        requestTimeline.removeFirst();
                        requestTimelineTruncated = true;
                    }
                    requestTimeline.addLast(committed);
                }
            }

            private void handleFetchRequestPaused(JsonNode node) {
                JsonNode params = node.path("params");
                String requestId = params.path("requestId").asText("");
                String networkId = params.path("networkId").asText("");
                String requestUrl = params.path("request").path("url").asText("");
                String resourceType = params.path("resourceType").asText("");
                try {
                    URI uri = new URI(requestUrl);
                    String scheme = uri.getScheme();
                    // data:, blob:, about: — браузерные схемы без сетевого запроса, пропускаем
                    if (scheme != null && !scheme.equals("http") && !scheme.equals("https")) {
                        markRequestContinued(networkId);
                        cdpContinue(requestId);
                        return;
                    }
                    // browser-level сессия — без фильтрации
                    if (allowedDomain == null) {
                        markRequestContinued(networkId);
                        cdpContinue(requestId);
                        return;
                    }
                    String host = uri.getHost();
                    String hostLower = host == null ? "" : host.toLowerCase(Locale.ROOT);
                    // Навигационный Document-запрос (включая redirect-хопы главной: http→https, на www,
                    // на другой хост/CDN) НЕ фильтруем по allowlist домена — мы сами выбрали этот URL
                    // в selectDynamicTargets, и allowedDomain снят со СТАРТОВОГО хоста, а не финального.
                    // Иначе редирект главной режется как third-party → Fetch.failRequest →
                    // net::ERR_BLOCKED_BY_CLIENT и весь dynamic-проход падает. SSRF-проверку ниже
                    // навигация всё равно проходит. Allowlist по домену остаётся для суб-ресурсов
                    // (script/img/xhr) ради pre-consent трекинга.
                    boolean isNavigation = "Document".equalsIgnoreCase(resourceType);
                    boolean allowed = isNavigation
                            || hostLower.equals(allowedDomain)
                            || hostLower.endsWith("." + allowedDomain)
                            || isHostAllowedBySet(hostLower, allowedThirdPartyHosts);
                    if (!allowed) {
                        logBlockedRequest(resourceType, hostLower, requestUrl, "third-party host is not allowed");
                        markRequestFailed(networkId);
                        cdpFail(requestId, "AccessDenied");
                        return;
                    }
                    // SSRF trust boundary (§5.4): даже разрешённый по имени хост обязан резолвиться
                    // в публичный IP. Без этого Chromium внутри headless-браузера может сходить на
                    // приватный/loopback адрес (DNS-rebinding, allowlist на внутренний хост) — этого
                    // не ловит static UrlValidator на границе SiteCrawler. Резолвим тем же валидатором.
                    if (!hostSafetyCache.computeIfAbsent(hostLower, urlValidator::isHostSafe)) {
                        logBlockedRequest(resourceType, hostLower, requestUrl, "host resolves to private/blocked IP");
                        markRequestFailed(networkId);
                        cdpFail(requestId, "AccessDenied");
                        return;
                    }
                    markRequestContinued(networkId);
                    cdpContinue(requestId);
                } catch (URISyntaxException e) {
                    markRequestFailed(networkId);
                    cdpFail(requestId, "AddressUnreachable");
                }
            }

            private void logBlockedRequest(String resourceType, String host, String requestUrl, String reason) {
                String key = (resourceType == null ? "" : resourceType) + "|" + host + "|" + reason;
                if (!blockedHostsLogged.add(key)) {
                    return;
                }
                if ("Document".equalsIgnoreCase(resourceType)) {
                    log.warn("CDP blocked document request host={} url={} reason={}", host, requestUrl, reason);
                } else {
                    log.debug("CDP blocked request type={} host={} url={} reason={}",
                            resourceType, host, requestUrl, reason);
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
    private PageAnalysisResult buildResult(PageFetch fetch) {
        String url = fetch.finalUrl();
        if (isBrowserInternalUrl(url)) {
            throw new IllegalStateException("Refusing to build PageAnalysisResult for browser internal URL: " + url);
        }
        Document doc = Jsoup.parse(fetch.html(), url);
        return PageExtractor.extract(url, doc, extractDomain(url), RenderMode.DYNAMIC,
                fetch.preConsentHosts(), fetch.preConsentCookies(), fetch.preConsentStorageKeys(),
                fetch.preConsentCookiesSnapshotAvailable(), fetch.preConsentStorageSnapshotAvailable(),
                fetch.consentScenario());
    }

    static boolean isBrowserInternalUrl(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("chrome-error://")
                || lower.startsWith("chrome://")
                || lower.startsWith("chrome-untrusted://")
                || lower.startsWith("devtools://")
                || lower.startsWith("about:")
                || lower.startsWith("edge://")
                || lower.startsWith("brave://");
    }

    private static String extractDomain(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * Чистое ядро правила «трекер до согласия»: из таймлайна первых запросов по хосту и момента
     * появления cookie-баннера определить сторонние хосты, чьи запросы ушли ДО согласия.
     * <p>
     * Семантика момента согласия (краулер НЕ кликает «Принять»): {@code bannerEpochMs == null}
     * означает «баннер не появился вовсе» → любой сторонний запрос считается pre-consent (трекинг
     * без всякого механизма согласия). Иначе pre-consent — только запросы строго раньше баннера.
     * <p>
     * Свой домен ({@code allowedDomain} и его поддомены) исключается: первый-party запросы — не
     * сторонний трекинг. Матчинг на справочник трекеров здесь НЕ делается — это ответственность
     * jurisdiction-зависимого правила. Времена — в единой шкале Unix-epoch мс ({@code wallTime}
     * запроса и {@code Date.now()} баннера), поэтому сравнимы напрямую.
     *
     * @param firstRequestEpochMsByHost хост (lowercase) → минимальный wallTime его запроса, мс
     * @param bannerEpochMs             момент появления баннера (epoch мс) или {@code null}, если не появился
     * @param allowedDomain             first-party домен (lowercase) для исключения; может быть {@code null}
     * @return отсортированные по времени сторонние хосты, запрошенные до согласия (порядок обхода стабилен)
     */
    static List<String> computePreConsentHosts(Map<String, Double> firstRequestEpochMsByHost,
                                               Double bannerEpochMs,
                                               String allowedDomain) {
        if (firstRequestEpochMsByHost == null || firstRequestEpochMsByHost.isEmpty()) {
            return List.of();
        }
        String firstParty = allowedDomain == null ? null : allowedDomain.toLowerCase(Locale.ROOT);
        return firstRequestEpochMsByHost.entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank())
                .filter(e -> !isFirstParty(e.getKey(), firstParty))
                .filter(e -> bannerEpochMs == null || e.getValue() < bannerEpochMs)
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .distinct()
                .toList();
    }

    /** Двойная граница исключает как старые события, так и запросы, начатые до Reject, но продолженные после. */
    static boolean isAfterRejectBoundary(long observationSequence, double observationEpochMs,
                                         long boundarySequence, double boundaryEpochMs) {
        return observationSequence > boundarySequence
                && (boundaryEpochMs <= 0 || observationEpochMs >= boundaryEpochMs);
    }

    private static boolean isFirstParty(String host, String firstPartyDomain) {
        if (firstPartyDomain == null || firstPartyDomain.isBlank()) {
            return false;
        }
        return host.equals(firstPartyDomain) || host.endsWith("." + firstPartyDomain);
    }
}
