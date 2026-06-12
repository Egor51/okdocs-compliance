package io.okdocs.compliance.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.contracts.crawler.CrawlerDiagnostics;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.RegistryStatus;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.rules.RuleEngine;
import io.okdocs.compliance.rules.RuleEngineResult;
import io.okdocs.compliance.worker.crawler.DynamicCrawler;
import io.okdocs.compliance.worker.crawler.SiteCrawler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Тяжёлая обработка одного скана (§5.2): краулинг → enrichment → правила → сборка findings → score
 * → диагностика. НЕ трогает статус скана и НЕ шлёт события — это делает {@link ScanLifecycleService}
 * после, в одной транзакции. Возвращает {@link ScanResult} + финальный статус по диагностике.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanPipeline {

    private final SiteCrawler siteCrawler;
    private final DynamicCrawler dynamicCrawler;
    private final HostCountryDetector hostCountryDetector;
    private final RknRegistryClient rknRegistryClient;
    private final RuleEngine ruleEngine;
    private final FindingAssembler findingAssembler;
    private final ScoreCalculator scoreCalculator;
    private final ScanProgressService progressService;
    private final ObjectMapper objectMapper;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final io.okdocs.compliance.worker.config.ComplianceWorkerProperties properties;

    /**
     * Результат анализа + рекомендованный финальный статус (COMPLETED / PARTIAL / FAILED).
     * {@code failureMessage} заполнен только для FAILED — конкретная причина (CDP/dynamic/0 страниц),
     * чтобы в БД/outbox попадал точный текст, а не общий «краулинг не вернул страниц».
     */
    public record PipelineOutcome(io.okdocs.compliance.contracts.enums.ScanStatus finalStatus,
                                  ScanResult result,
                                  String failureMessage) {

        public static PipelineOutcome failed(String message) {
            return new PipelineOutcome(io.okdocs.compliance.contracts.enums.ScanStatus.FAILED, null, message);
        }

        public static PipelineOutcome of(io.okdocs.compliance.contracts.enums.ScanStatus status, ScanResult result) {
            return new PipelineOutcome(status, result, null);
        }
    }

    public PipelineOutcome run(ComplianceScan scan, UUID scanId) {
        String siteUrl = scan.getSiteUrl();
        String domain = scan.getSiteDomain();
        long deadlineMs = System.currentTimeMillis()
                + properties.getScan().getTotalDeadline().toMillis();

        // Dynamic required (CABINET_PREMIUM): если CDP недоступен — НЕ отдаём degraded static за деньги,
        // валим скан до краула. ScanRequestedListener → lifecycle.fail → ScanFailedEvent → refund.
        if (scan.isDynamicRequired() && !dynamicCrawler.isAvailable()) {
            log.warn("Scan {} requires dynamic but CDP unavailable → FAILED (refund)", scanId);
            return PipelineOutcome.failed("Динамический анализ недоступен (CDP)");
        }

        progressService.updateProgress(scanId, 10, "Краулинг сайта");
        SiteCrawler.CrawlResult crawl = siteCrawler.crawl(siteUrl, scan.getMaxPages());
        CrawlerDiagnostics diag = crawl.diagnostics();

        // pagesFetched == 0 → анализировать нечего → FAILED (событие ScanFailedEvent сформирует lifecycle).
        if (diag.pagesFetched() == 0) {
            log.warn("Scan {} fetched 0 pages → FAILED", scanId);
            return PipelineOutcome.failed("Краулинг не вернул ни одной страницы");
        }

        // Гибридный static+dynamic (§5.4): дорогой headless-проход — только для CABINET_PREMIUM с
        // dynamicRequired. Перекраулить приоритетные страницы в браузере и наложить DYNAMIC поверх
        // STATIC (на STATIC часть нарушений вероятностна — POSSIBLE_*/UNVERIFIED, DYNAMIC строг).
        // FREE_MARKETING остаётся на static. Режим — из строки скана (kind/dynamicRequired), не из события.
        boolean dynamicEnabled = scan.getKind() == ScanKind.CABINET_PREMIUM && scan.isDynamicRequired();
        List<PageAnalysisResult> pages;
        try {
            pages = maybeDynamicRecrawl(crawl.pages(), domain, scanId, dynamicEnabled);
        } catch (DynamicRequiredFailedException e) {
            // premium с dynamicRequired: динамика предпринята и провалилась → FAILED + refund,
            // не отдаём degraded static за деньги (консистентно с pre-crawl проверкой CDP).
            log.warn("Scan {} dynamic required but failed → FAILED (refund): {}", scanId, e.getMessage());
            return PipelineOutcome.failed("Динамический анализ не выполнен: " + e.getMessage());
        }

        progressService.updateProgress(scanId, 60, "Анализ соответствия");

        // Enrichment ДО RuleEngine (правила остаются чистыми функциями ctx → facts).
        String hostCountry = hostCountryDetector.detectCountry(domain).orElse(null);
        List<String> resolvedIps = hostCountryDetector.resolveIps(domain);
        RegistryStatus registryStatus = rknRegistryClient.lookup(domain, null);

        // jurisdiction («по какому закону проверяем») — из строки скана, не из hostCountry:
        // RuleEngine по нему выбирает набор правил (RU=152-ФЗ / EU=GDPR).
        ScanAnalysisContext ctx = new ScanAnalysisContext(
                scan.getJurisdiction(), pages, hostCountry, resolvedIps, registryStatus, diag);

        RuleEngineResult engineResult = ruleEngine.evaluate(ctx);
        List<ComplianceFinding> findings = findingAssembler.assemble(scanId, engineResult.facts());
        int score = scoreCalculator.calculate(findings);

        // Observability: метрики краула и ошибок правил (§5.7).
        meterRegistry.counter("compliance.crawl.pages.fetched").increment(diag.pagesFetched());
        meterRegistry.counter("compliance.crawl.pages.failed").increment(diag.pagesFailed());
        if (engineResult.errors() != null && !engineResult.errors().isEmpty()) {
            meterRegistry.counter("compliance.rule.errors").increment(engineResult.errors().size());
        }

        String diagnosticsJson = serializeDiagnostics(diag, engineResult);

        progressService.updateProgress(scanId, 90, "Формирование отчёта");

        // Общий дедлайн скана (§5.7): страховка от зависания НЕ в краулере (у него свой timeout), а в
        // enrichment/правилах. Превышение → PARTIAL: результат есть, но процесс затянулся.
        boolean deadlineExceeded = System.currentTimeMillis() > deadlineMs;
        if (deadlineExceeded) {
            log.warn("Scan {} exceeded total deadline ({}) → PARTIAL",
                    scanId, properties.getScan().getTotalDeadline());
        }

        // pagesFailed > 0 || crawlerTimedOut || превышен общий дедлайн → PARTIAL; иначе COMPLETED.
        var status = (diag.pagesFailed() > 0 || diag.crawlerTimedOut() || deadlineExceeded)
                ? io.okdocs.compliance.contracts.enums.ScanStatus.PARTIAL
                : io.okdocs.compliance.contracts.enums.ScanStatus.COMPLETED;

        return PipelineOutcome.of(status,
                new ScanResult(findings, score, diag.pagesFetched(), diagnosticsJson));
    }

    /** Сигнал: dynamic-проход был обязателен (dynamicRequired) и провалился — premium → FAILED+refund. */
    static final class DynamicRequiredFailedException extends RuntimeException {
        DynamicRequiredFailedException(String message) {
            super(message);
        }
    }

    /**
     * Гибридное обогащение: перекраулить приоритетные страницы через {@link DynamicCrawler} и
     * наложить DYNAMIC-версии поверх STATIC (по URL).
     * <p>
     * Семантика при {@code dynamicEnabled} (= CABINET_PREMIUM + dynamicRequired): dynamic-проход
     * обязателен. Берём стартовую страницу, приоритетные URL (формы/политика/контакты) и fallback
     * из первых static-страниц. Если CDP упал или вернул пусто — это
     * {@link DynamicRequiredFailedException} → весь скан FAILED + refund (не отдаём degraded static
     * за деньги).
     */
    private List<PageAnalysisResult> maybeDynamicRecrawl(List<PageAnalysisResult> staticPages,
                                                         String domain, UUID scanId, boolean dynamicEnabled) {
        if (!dynamicEnabled) {
            return staticPages;
        }
        if (staticPages.isEmpty()) {
            return staticPages; // 0 static-страниц уже отсечены раньше (pagesFetched==0 → FAILED)
        }
        // CDP стал недоступен после pre-crawl проверки → premium без dynamic недопустим.
        if (!dynamicCrawler.isAvailable()) {
            meterRegistry.counter("compliance.dynamic.failure").increment();
            throw new DynamicRequiredFailedException("CDP became unavailable during premium scan");
        }
        int maxDynamicPages = properties.getCrawler().getDynamic().getMaxPages();
        List<String> targets = selectDynamicTargets(staticPages, maxDynamicPages);
        if (targets.isEmpty()) {
            // staticPages не пустой, поэтому сюда попадём только если у всех страниц пустой URL.
            log.info("Scan {} premium: no valid URLs for dynamic re-crawl, static result is complete", scanId);
            return staticPages;
        }

        // Доверенные сторонние хосты для CDP-фильтра: внешние script/style-домены, уже найденные
        // на static — их НЕ режем, иначе трекеры не подгрузятся и dynamic не подтвердит нарушение.
        java.util.Set<String> allowedThirdParty = new java.util.LinkedHashSet<>();
        for (PageAnalysisResult p : staticPages) {
            if (p.externalScriptDomains() != null) {
                allowedThirdParty.addAll(p.externalScriptDomains());
            }
            if (p.externalStyleDomains() != null) {
                allowedThirdParty.addAll(p.externalStyleDomains());
            }
        }

        Map<String, PageAnalysisResult> dynamic;
        try {
            progressService.updateProgress(scanId, 50, "Динамический анализ (рендеринг)");
            dynamic = dynamicCrawler.crawlPages(targets, allowedThirdParty);
        } catch (Exception e) {
            meterRegistry.counter("compliance.dynamic.failure").increment();
            // dynamicRequired: проход предпринят и упал → весь скан FAILED + refund.
            throw new DynamicRequiredFailedException("Dynamic re-crawl failed: " + e.getMessage());
        }
        if (dynamic.isEmpty()) {
            meterRegistry.counter("compliance.dynamic.failure").increment();
            throw new DynamicRequiredFailedException("Dynamic re-crawl returned no pages");
        }
        // DYNAMIC заменяет STATIC по тому же URL, порядок static сохраняется.
        Map<String, PageAnalysisResult> byUrl = new LinkedHashMap<>();
        for (PageAnalysisResult p : staticPages) {
            byUrl.put(p.url(), p);
        }
        dynamic.forEach(byUrl::put);
        meterRegistry.counter("compliance.dynamic.success").increment();
        log.info("Scan {} dynamic re-crawl: {} of {} target pages enriched",
                scanId, dynamic.size(), targets.size());
        return new ArrayList<>(byUrl.values());
    }

    /**
     * Для premium dynamicRequired CDP не должен зависеть только от эвристики "есть форма/политика".
     * SPA может скрывать формы/трекеры до JS-рендера, поэтому всегда рендерим стартовую страницу,
     * затем приоритетные страницы и добиваем первыми URL из static crawl до лимита.
     */
    private static List<String> selectDynamicTargets(List<PageAnalysisResult> staticPages, int maxDynamicPages) {
        Set<String> targets = new LinkedHashSet<>();
        addDynamicTarget(targets, staticPages.get(0));

        for (PageAnalysisResult page : staticPages) {
            if (targets.size() >= maxDynamicPages) {
                break;
            }
            if (isWorthDynamicRecrawl(page)) {
                addDynamicTarget(targets, page);
            }
        }

        for (PageAnalysisResult page : staticPages) {
            if (targets.size() >= maxDynamicPages) {
                break;
            }
            addDynamicTarget(targets, page);
        }
        return new ArrayList<>(targets);
    }

    private static void addDynamicTarget(Set<String> targets, PageAnalysisResult page) {
        if (page == null || page.url() == null || page.url().isBlank()) {
            return;
        }
        targets.add(page.url());
    }

    /** Приоритетная страница для dynamic-перекраула: формы, политика, consent, контакты. */
    private static boolean isWorthDynamicRecrawl(PageAnalysisResult page) {
        if (page.forms() != null && !page.forms().isEmpty()) {
            return true;
        }
        String url = page.url() == null ? "" : page.url().toLowerCase(java.util.Locale.ROOT);
        return url.contains("privac") || url.contains("polic") || url.contains("политик")
                || url.contains("consent") || url.contains("personal") || url.contains("contact");
    }

    /** Слияние метрик краулера и ошибок правил в один JSON для {@code diagnostics_json} (§2.2). */
    private String serializeDiagnostics(CrawlerDiagnostics diag, RuleEngineResult engineResult) {
        Map<String, Object> merged = Map.of(
                "pagesAttempted", diag.pagesAttempted(),
                "pagesFetched", diag.pagesFetched(),
                "pagesFailed", diag.pagesFailed(),
                "crawlerTimedOut", diag.crawlerTimedOut(),
                "ruleErrors", engineResult.errors(),
                "ruleOutcomes", engineResult.outcomes());
        try {
            return objectMapper.writeValueAsString(merged);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize diagnostics: {}", e.getMessage());
            return null;
        }
    }
}
