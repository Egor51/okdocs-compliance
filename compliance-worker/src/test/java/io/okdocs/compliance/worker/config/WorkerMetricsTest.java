package io.okdocs.compliance.worker.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт фасада метрик (этап A observability): фиксирует ИМЕНА и ТЕГИ метрик, на которые
 * завязаны Grafana-дашборд и Prometheus-алерты. Если кто-то переименует метрику/тег — упадёт здесь,
 * а не молча потеряет временной ряд в проде.
 */
class WorkerMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WorkerMetrics metrics = new WorkerMetrics(registry);

    @Test
    void scanOutcomeCountsByKindJurisdictionStatus() {
        metrics.scanOutcome(ScanKind.CABINET_PREMIUM, ScanJurisdiction.RU, ScanStatus.PARTIAL);

        double count = registry.get("compliance.scan.outcome")
                .tag("kind", "CABINET_PREMIUM")
                .tag("jurisdiction", "RU")
                .tag("status", "PARTIAL")
                .counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void dynamicResultsShareOneCounterWithResultTag() {
        metrics.dynamicResult(WorkerMetrics.DynamicResult.SUCCESS);
        metrics.dynamicResult(WorkerMetrics.DynamicResult.FAILURE);
        metrics.dynamicResult(WorkerMetrics.DynamicResult.FAILURE);

        assertThat(registry.get("compliance.dynamic").tag("result", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("compliance.dynamic").tag("result", "failure").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    void recordFindingsCountsBySeverityAndCategory() {
        metrics.recordFindings(List.of(
                finding(FindingSeverity.CRITICAL, FindingCategory.SECURITY),
                finding(FindingSeverity.CRITICAL, FindingCategory.SECURITY),
                finding(FindingSeverity.LOW, FindingCategory.COOKIES)));

        assertThat(registry.get("compliance.findings")
                .tag("severity", "CRITICAL").tag("category", "SECURITY").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("compliance.findings")
                .tag("severity", "LOW").tag("category", "COOKIES").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void recordPhaseTagsByPhaseAndKind() {
        metrics.recordPhase(WorkerMetrics.PHASE_DYNAMIC_RECRAWL, ScanKind.CABINET_PREMIUM, 1234);

        assertThat(registry.get("compliance.phase")
                .tag("phase", "dynamic-recrawl").tag("kind", "CABINET_PREMIUM")
                .timer().count())
                .isEqualTo(1L);
    }

    @Test
    void zeroCountsDoNotCreateSeries() {
        // Краул без страниц и без ошибок не должен плодить нулевые серии.
        metrics.recordCrawlPages(0, 0);
        metrics.recordRuleErrors(0);

        assertThat(registry.find("compliance.crawl.pages.fetched").counter()).isNull();
        assertThat(registry.find("compliance.rule.errors").counter()).isNull();
    }

    private static ComplianceFinding finding(FindingSeverity severity, FindingCategory category) {
        ComplianceFinding f = new ComplianceFinding();
        f.setSeverity(severity);
        f.setCategory(category);
        return f;
    }
}
