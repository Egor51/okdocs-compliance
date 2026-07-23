package io.okdocs.compliance.worker.job;

import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.event.ScanRequestedEvent;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import io.okdocs.compliance.worker.service.ScanLifecycleService;
import io.okdocs.compliance.worker.service.ScanPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Главный consumer worker'а (§5.2): оркестрирует пайплайн {@code CRAWLING → ANALYZING →
 * COMPLETED | PARTIAL | FAILED}. Manual ack, concurrency из конфига.
 * <p>
 * <b>Idempotency-гард по {@code scan.status}</b> (at-least-once Kafka + transactional outbox дают
 * дубли). Право выполнения выдаёт атомарный переход {@code QUEUED → CRAWLING}. Все остальные
 * доставки поглощаются: уже работающий скан нельзя запускать повторно, а потерянного владельца
 * завершит reaper по общему deadline.
 * <table>
 *   <tr><th>status</th><th>действие</th><th>ack?</th></tr>
 *   <tr><td>терминальный</td><td>не обрабатывать (дубль / добит reaper'ом)</td><td>да, поглотить</td></tr>
 *   <tr><td>QUEUED</td><td>обработать</td><td>да, после коммита</td></tr>
 *   <tr><td>CRAWLING/ANALYZING</td><td>не обрабатывать (владелец уже выбран)</td><td>да</td></tr>
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
    private final io.okdocs.compliance.worker.config.WorkerMetrics metrics;

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
            MDC.remove("incidentId");
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
            // Execution ownership is persisted by the atomic QUEUED→CRAWLING claim. A redelivery
            // must never restart or refresh an in-flight scan. Reaper handles a dead owner.
            log.debug("Scan {} already in progress ({}) — duplicate, acking", scanId, status);
            acknowledgment.acknowledge();
            return;
        }

        if (status != ScanStatus.QUEUED) {
            log.warn("Scan {} has unsupported non-terminal status {} — acking", scanId, status);
            acknowledgment.acknowledge();
            return;
        }

        if (!lifecycle.claimForProcessing(scanId)) {
            log.debug("Scan {} claim lost to another worker — duplicate, acking", scanId);
            acknowledgment.acknowledge();
            return;
        }

        // This delivery owns processing after the committed atomic claim.
        try {
            process(scan);
            acknowledgment.acknowledge(); // ack только после успешного коммита финального статуса
        } catch (Exception e) {
            metrics.listenerFailure();
            UUID incidentId = UUID.randomUUID();
            MDC.put("incidentId", incidentId.toString());
            log.error("Pipeline failed for scan {} (incidentId={}): {}",
                    scanId, incidentId, e.getMessage(), e);
            // Финальный fail коммитим в БД (+ ScanFailedEvent через outbox), затем ack — иначе
            // бесконечный redelivery упавшего скана. Reaper подстрахует, если этот fail тоже упадёт.
            try {
                lifecycle.fail(scanId,
                        io.okdocs.compliance.worker.service.ScanFailures.internal(incidentId));
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
        // Режим выполнения (maxPages/kind/dynamicRequired) worker берёт из строки скана, не из события.
        ScanPipeline.PipelineOutcome outcome = pipeline.run(scan, scanId);

        switch (outcome.finalStatus()) {
            case FAILED -> lifecycle.fail(scanId, outcome.failure());
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

}
