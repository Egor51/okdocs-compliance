package io.okdocs.compliance.worker.job;

import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.event.ScanRequestedEvent;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import io.okdocs.compliance.worker.service.ScanLifecycleService;
import io.okdocs.compliance.worker.service.ScanPipeline;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Главный consumer worker'а (§5.2): оркестрирует пайплайн {@code CRAWLING → ANALYZING →
 * COMPLETED | PARTIAL | FAILED}. Manual ack, concurrency из конфига.
 * <p>
 * <b>Idempotency-гард по {@code scan.status}</b> (at-least-once Kafka + transactional outbox дают
 * дубли). Ключевое различие — «не обрабатывать» vs «не подтверждать»: дубликат нужно <b>поглотить
 * (ack)</b>, иначе Kafka передоставляет вечно; но скан, идущий <b>сейчас</b> в другом потоке/инстансе,
 * подтверждать нельзя — пусть передоставится позже.
 * <table>
 *   <tr><th>status</th><th>действие</th><th>ack?</th></tr>
 *   <tr><td>терминальный</td><td>не обрабатывать (дубль / добит reaper'ом)</td><td>да, поглотить</td></tr>
 *   <tr><td>QUEUED</td><td>обработать</td><td>да, после коммита</td></tr>
 *   <tr><td>CRAWLING/ANALYZING, свежий</td><td>не обрабатывать (идёт сейчас)</td><td><b>нет</b></td></tr>
 *   <tr><td>CRAWLING/ANALYZING, протух</td><td>перезапустить (worker упал)</td><td>да, после коммита</td></tr>
 * </table>
 * Финальный переход + событие (через outbox) делает {@link ScanLifecycleService} в одной транзакции;
 * напрямую в Kafka не шлём.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanRequestedListener {

    private final ComplianceScanRepository scanRepository;
    private final ScanPipeline pipeline;
    private final ScanLifecycleService lifecycle;
    private final ComplianceWorkerProperties properties;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
            topics = "${compliance.kafka.topic.scan-requested}",
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "${spring.kafka.listener.concurrency:3}")
    public void onScanRequested(ScanRequestedEvent event, Acknowledgment acknowledgment) {
        UUID scanId = event.scanId();
        // Structured logs: scanId в MDC на всё время обработки — pipeline/lifecycle-логи подхватят.
        MDC.put("scanId", String.valueOf(scanId));
        try {
            handle(event, acknowledgment, scanId);
        } finally {
            MDC.remove("scanId");
            MDC.remove("scanKind");
        }
    }

    private void handle(ScanRequestedEvent event, Acknowledgment acknowledgment, UUID scanId) {
        Optional<ComplianceScan> maybeScan = scanRepository.findById(scanId);
        if (maybeScan.isEmpty()) {
            // Скан-строки нет (откатилась транзакция api?) — поглощаем, ретраить бессмысленно.
            log.warn("ScanRequestedEvent for unknown scan {} — acking to drop", scanId);
            acknowledgment.acknowledge();
            return;
        }

        ComplianceScan scan = maybeScan.get();
        MDC.put("scanKind", String.valueOf(scan.getKind()));
        ScanStatus status = scan.getStatus();

        if (status.isTerminal()) {
            log.debug("Scan {} already terminal ({}) — duplicate, acking", scanId, status);
            acknowledgment.acknowledge();
            return;
        }

        if (status == ScanStatus.CRAWLING || status == ScanStatus.ANALYZING) {
            if (isFresh(scan)) {
                // Идёт сейчас в другом потоке/инстансе — нельзя ни обрабатывать, ни ack'ать.
                // nack(delay): контейнер переставит offset на это сообщение и повторит позже
                // (с паузой), без подтверждения. Голый return offset не зафиксирует, но и
                // повторной доставки не вызовет до rebalance/restart — поэтому именно nack.
                Duration delay = properties.getScan().getRedeliverDelay();
                log.debug("Scan {} in progress ({}, fresh) — nack, redeliver in {}", scanId, status, delay);
                acknowledgment.nack(delay);
                return;
            }
            log.info("Scan {} stale in {} — restarting (worker likely crashed)", scanId, status);
        }

        // QUEUED либо протухший in-progress → обрабатываем.
        try {
            process(scan);
            acknowledgment.acknowledge(); // ack только после успешного коммита финального статуса
        } catch (Exception e) {
            meterRegistry.counter("compliance.scan.listener.failures").increment();
            log.error("Pipeline failed for scan {}: {}", scanId, e.getMessage(), e);
            // Финальный fail коммитим в БД (+ ScanFailedEvent через outbox), затем ack — иначе
            // бесконечный redelivery упавшего скана. Reaper подстрахует, если этот fail тоже упадёт.
            try {
                lifecycle.fail(scanId, "Pipeline error: " + e.getClass().getSimpleName());
                acknowledgment.acknowledge();
            } catch (Exception failEx) {
                // Даже FAILED не закоммитился (БД недоступна?) — nack для управляемой повторной
                // доставки; reaper подстрахует по staleAfter, если и повтор не пройдёт.
                Duration delay = properties.getScan().getRedeliverDelay();
                log.error("Failed to mark scan {} FAILED — nack, redeliver in {}: {}",
                        scanId, delay, failEx.getMessage());
                acknowledgment.nack(delay);
            }
        }
    }

    private void process(ComplianceScan scan) {
        UUID scanId = scan.getId();
        lifecycle.markCrawling(scanId);
        // Режим выполнения (maxPages/kind/dynamicRequired) worker берёт из строки скана, не из события.
        ScanPipeline.PipelineOutcome outcome = pipeline.run(scan, scanId);

        switch (outcome.finalStatus()) {
            case FAILED -> lifecycle.fail(scanId, outcome.failureMessage());
            case PARTIAL -> {
                lifecycle.markAnalyzing(scanId);
                lifecycle.partial(scanId, outcome.result());
            }
            case COMPLETED -> {
                lifecycle.markAnalyzing(scanId);
                lifecycle.complete(scanId, outcome.result());
            }
            default -> throw new IllegalStateException("Unexpected final status " + outcome.finalStatus());
        }
    }

    /** «Свежий» in-progress: обновлялся недавно (< staleAfter) — значит идёт сейчас, не завис. */
    private boolean isFresh(ComplianceScan scan) {
        Duration staleAfter = properties.getScan().getStaleAfter();
        Instant cutoff = Instant.now().minus(staleAfter);
        return scan.getUpdatedAt() != null && scan.getUpdatedAt().isAfter(cutoff);
    }
}
