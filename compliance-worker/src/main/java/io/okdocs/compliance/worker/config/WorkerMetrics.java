package io.okdocs.compliance.worker.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Единая точка инструментации воркера (§5.7, этап A observability). Раньше метрики
 * {@code compliance.*} ставились строковыми литералами {@code meterRegistry.counter("...")}
 * по 4 классам — опечатка в имени/теге тихо теряла временной ряд, не было обзора кардинальности.
 * <p>
 * <b>Инвариант кардинальности зашит в сигнатуры:</b> теги принимаются только как enum'ы
 * ({@link ScanKind}/{@link ScanStatus}/{@link ScanJurisdiction}/severity/category) или
 * фиксированные строки фаз — нельзя случайно положить {@code scanId}/{@code domain}/{@code siteUrl}
 * в тег и взорвать Prometheus. Идентификаторы прогона живут в trace/MDC, агрегаты — здесь.
 * <p>
 * Имена метрик в Prometheus (Micrometer-конвенция): точки→подчёркивания, counter получает
 * суффикс {@code _total}, timer — {@code _seconds_*}. Гистограммы для {@code compliance.*}-таймеров
 * включены в {@code application.yml} (percentiles-histogram) — иначе p95 в Grafana пуст.
 */
@Component
@RequiredArgsConstructor
public class WorkerMetrics {

    private final MeterRegistry registry;

    // ── Фазы пайплайна (тяжёлые: static-crawl / dynamic-recrawl) ──────────────────
    // Заменяет ручные System.currentTimeMillis() + timer("compliance.phase","phase",...).

    public static final String PHASE_STATIC_CRAWL = "static-crawl";
    public static final String PHASE_DYNAMIC_RECRAWL = "dynamic-recrawl";

    /** Длительность фазы пайплайна. {@code compliance.phase{phase,kind}}. */
    public void recordPhase(String phase, ScanKind kind, long elapsedMs) {
        registry.timer("compliance.phase", "phase", phase, "kind", String.valueOf(kind))
                .record(Duration.ofMillis(elapsedMs));
    }

    /** Длительность enrichment-шага (dns/rkn/tls). {@code compliance.enrichment{step}}. */
    public void recordEnrichment(String step, long elapsedMs) {
        registry.timer("compliance.enrichment", "step", step)
                .record(Duration.ofMillis(elapsedMs));
    }

    // ── Исход скана: НОВАЯ метрика — главный SLO-срез (доля PARTIAL/FAILED free vs premium) ──

    /** Терминальный исход скана. {@code compliance.scan.outcome{kind,jurisdiction,status}}. */
    public void scanOutcome(ScanKind kind, ScanJurisdiction jurisdiction, ScanStatus status) {
        registry.counter("compliance.scan.outcome",
                        "kind", String.valueOf(kind),
                        "jurisdiction", String.valueOf(jurisdiction),
                        "status", String.valueOf(status))
                .increment();
    }

    /** Длительность скана. {@code compliance.scan.duration{status,kind}} (перенесено из lifecycle). */
    public void recordScanDuration(ScanStatus status, ScanKind kind, long durationMs) {
        registry.timer("compliance.scan.duration",
                        "status", String.valueOf(status),
                        "kind", String.valueOf(kind))
                .record(Duration.ofMillis(durationMs));
    }

    // ── Findings: продуктовый срез по severity/category без обращения к БД ────────

    /** Срез найденных нарушений. {@code compliance.findings{severity,category}}. */
    public void recordFindings(List<ComplianceFinding> findings) {
        if (findings == null) {
            return;
        }
        for (ComplianceFinding f : findings) {
            registry.counter("compliance.findings",
                            "severity", String.valueOf(f.getSeverity()),
                            "category", String.valueOf(f.getCategory()))
                    .increment();
        }
    }

    // ── Краул ─────────────────────────────────────────────────────────────────────

    /** Страницы краула. {@code compliance.crawl.pages.fetched/failed}. */
    public void recordCrawlPages(int fetched, int failed) {
        if (fetched > 0) {
            registry.counter("compliance.crawl.pages.fetched").increment(fetched);
        }
        if (failed > 0) {
            registry.counter("compliance.crawl.pages.failed").increment(failed);
        }
    }

    // ── Dynamic (CDP): один counter с тегом result вместо трёх отдельных метрик ────

    public enum DynamicResult { SUCCESS, FAILURE, DEGRADED }

    /** Исход dynamic-прохода. {@code compliance.dynamic{result}} (объединяет success/failure/degraded). */
    public void dynamicResult(DynamicResult result) {
        registry.counter("compliance.dynamic", "result", result.name().toLowerCase())
                .increment();
    }

    // ── Ошибки ──────────────────────────────────────────────────────────────────

    /** Ошибки правил. {@code compliance.rule.errors}. */
    public void recordRuleErrors(int count) {
        if (count > 0) {
            registry.counter("compliance.rule.errors").increment(count);
        }
    }

    /** Падение листенера (исключение пайплайна до финализации). {@code compliance.scan.listener.failures}. */
    public void listenerFailure() {
        registry.counter("compliance.scan.listener.failures").increment();
    }

    /** Reaper добил зависший скан. {@code compliance.reaper.failed}. */
    public void reaperFailed() {
        registry.counter("compliance.reaper.failed").increment();
    }
}
