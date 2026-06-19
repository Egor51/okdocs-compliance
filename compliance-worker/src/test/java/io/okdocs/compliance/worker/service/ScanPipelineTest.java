package io.okdocs.compliance.worker.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.okdocs.compliance.contracts.crawler.CrawlerDiagnostics;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.enums.RenderMode;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.rules.RuleEngine;
import io.okdocs.compliance.rules.RuleEngineResult;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import io.okdocs.compliance.worker.crawler.DynamicCrawler;
import io.okdocs.compliance.worker.crawler.SiteCrawler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Поведение {@link ScanPipeline} для CABINET_PREMIUM + dynamicRequired: если динамика проваливается,
 * но static-страницы есть (типично для одностраничного SPA), скан деградирует на static → PARTIAL
 * с непустым результатом (а не FAILED+refund). FAILED остаётся только когда нечего анализировать
 * (CDP недоступен ещё до краула / 0 static-страниц).
 */
@ExtendWith(MockitoExtension.class)
class ScanPipelineTest {

    @Mock SiteCrawler siteCrawler;
    @Mock DynamicCrawler dynamicCrawler;
    @Mock io.okdocs.compliance.worker.crawler.TlsInspector tlsInspector;
    @Mock DnsInspector dnsInspector;
    @Mock RknRegistryClient rknRegistryClient;
    @Mock RuleEngine ruleEngine;
    @Mock FindingAssembler findingAssembler;
    @Mock ScoreCalculator scoreCalculator;
    @Mock ScanProgressService progressService;

    ScanPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new ScanPipeline(siteCrawler, dynamicCrawler, tlsInspector, dnsInspector,
                rknRegistryClient, ruleEngine, findingAssembler, scoreCalculator, progressService,
                new ObjectMapper(), new SimpleMeterRegistry(), new ComplianceWorkerProperties());
    }

    @Test
    void premium_cdpUnavailableBeforeCrawl_failsFast() {
        ComplianceScan scan = premiumScan();
        when(dynamicCrawler.isAvailable()).thenReturn(false);

        ScanPipeline.PipelineOutcome outcome = pipeline.run(scan, scan.getId());

        assertThat(outcome.finalStatus()).isEqualTo(ScanStatus.FAILED);
        assertThat(outcome.result()).isNull();
        // Конкретная причина (не общий «краулинг не вернул страниц») — попадёт в БД/outbox.
        assertThat(outcome.failureMessage()).contains("CDP");
        // до краула не дошли
        org.mockito.Mockito.verifyNoInteractions(siteCrawler);
    }

    @Test
    void premium_dynamicReturnsEmpty_degradesToPartialOnStatic() {
        ComplianceScan scan = premiumScan();
        when(dynamicCrawler.isAvailable()).thenReturn(true);
        // static вернул страницу, достойную dynamic (URL с "privacy")
        var page = page("https://example.com/privacy");
        when(siteCrawler.crawl(anyString(), anyInt()))
                .thenReturn(new SiteCrawler.CrawlResult(List.of(page), List.of(), new CrawlerDiagnostics(1, 1, 0, false)));
        // dynamic вернул пусто → деградируем на static → PARTIAL с результатом (не FAILED+refund)
        when(dynamicCrawler.crawlPages(any(), any())).thenReturn(Map.of());
        stubAnalysis();

        ScanPipeline.PipelineOutcome outcome = pipeline.run(scan, scan.getId());

        assertThat(outcome.finalStatus()).isEqualTo(ScanStatus.PARTIAL);
        assertThat(outcome.result()).isNotNull();
    }

    @Test
    void premium_dynamicThrows_degradesToPartialOnStatic() {
        ComplianceScan scan = premiumScan();
        when(dynamicCrawler.isAvailable()).thenReturn(true);
        var page = page("https://example.com/privacy");
        when(siteCrawler.crawl(anyString(), anyInt()))
                .thenReturn(new SiteCrawler.CrawlResult(List.of(page), List.of(), new CrawlerDiagnostics(1, 1, 0, false)));
        when(dynamicCrawler.crawlPages(any(), any())).thenThrow(new RuntimeException("CDP boom"));
        stubAnalysis();

        ScanPipeline.PipelineOutcome outcome = pipeline.run(scan, scan.getId());

        assertThat(outcome.finalStatus()).isEqualTo(ScanStatus.PARTIAL);
        assertThat(outcome.result()).isNotNull();
    }

    @Test
    void premium_noPriorityPagesStillRunsDynamicFallback() {
        // Нет форм/политики в static HTML: для premium всё равно рендерим fallback-страницы,
        // потому что SPA может показать формы/трекеры только после JS.
        ComplianceScan scan = premiumScan();
        when(dynamicCrawler.isAvailable()).thenReturn(true);
        var page = page("https://example.com/about"); // не приоритетная dynamic-страница
        when(siteCrawler.crawl(anyString(), anyInt()))
                .thenReturn(new SiteCrawler.CrawlResult(List.of(page), List.of(), new CrawlerDiagnostics(1, 1, 0, false)));
        when(dynamicCrawler.crawlPages(eq(List.of("https://example.com/about")), any()))
                .thenReturn(Map.of("https://example.com/about", dynamicPage("https://example.com/about")));
        stubAnalysis();

        ScanPipeline.PipelineOutcome outcome = pipeline.run(scan, scan.getId());

        assertThat(outcome.finalStatus()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(outcome.result()).isNotNull();
        org.mockito.Mockito.verify(dynamicCrawler).crawlPages(eq(List.of("https://example.com/about")), any());
    }

    private void stubAnalysis() {
        lenient().when(tlsInspector.inspect(anyString(), org.mockito.ArgumentMatchers.anyList())).thenReturn(
                new io.okdocs.compliance.contracts.crawler.TlsInfo(
                        "example.com", true, null, "TLSv1.3", "TLS_AES_128_GCM_SHA256",
                        null, null, List.of(), null, null));
        when(dnsInspector.inspect(anyString())).thenReturn(
                new io.okdocs.compliance.contracts.crawler.DnsInfo(
                        "example.com", false, "RU", List.of("203.0.113.1"), List.of("RU"),
                        List.of(), List.of(), List.of()));
        when(rknRegistryClient.lookup(anyString(), any()))
                .thenReturn(io.okdocs.compliance.contracts.enums.RegistryStatus.LOOKUP_FAILED);
        when(ruleEngine.evaluate(any())).thenReturn(new RuleEngineResult(List.of(), List.of()));
        when(findingAssembler.assemble(any(), any())).thenReturn(List.of());
        lenient().when(scoreCalculator.calculate(any())).thenReturn(100);
    }

    private ComplianceScan premiumScan() {
        ComplianceScan scan = new ComplianceScan();
        scan.setId(UUID.randomUUID());
        scan.setSiteUrl("https://example.com");
        scan.setSiteDomain("example.com");
        scan.setKind(ScanKind.CABINET_PREMIUM);
        scan.setMaxPages(30);
        scan.setDynamicRequired(true);
        return scan;
    }

    private PageAnalysisResult page(String url) {
        return new PageAnalysisResult(url, "t", "text", "<html></html>",
                List.of(), List.of(), List.of(), false, List.of(), RenderMode.STATIC);
    }

    private PageAnalysisResult dynamicPage(String url) {
        return new PageAnalysisResult(url, "t", "text", "<html></html>",
                List.of(), List.of(), List.of(), false, List.of(), RenderMode.DYNAMIC);
    }
}
