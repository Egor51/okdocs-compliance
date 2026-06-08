package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.event.ScanCompletedEvent;
import io.okdocs.compliance.contracts.event.ScanFailedEvent;
import io.okdocs.compliance.messaging.OutboxEventFactory;
import io.okdocs.compliance.persistence.outbox.OutboxEvent;
import io.okdocs.compliance.persistence.outbox.OutboxEventRepository;
import io.okdocs.compliance.persistence.scan.ComplianceFindingRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Единственная точка <b>строгих</b> транзакционных переходов статуса скана (§5.3): и листенер
 * (§5.2), и reaper зовут её — дублирования {@code scan.fail()} + {@code outbox.save()} нет.
 * {@code complete}/{@code partial}/{@code fail} пишут {@code OutboxEvent} в <b>той же транзакции</b>,
 * что и статус + findings (transactional outbox; не шлём в Kafka напрямую).
 * <p>
 * Каждый метод {@code @Transactional}, проверяет инвариант перехода (из терминального не уходим
 * обратно). {@code OptimisticLockingFailureException} здесь — значимый сигнал конфликта, НЕ
 * проглатывается (вызывающий решает) — в отличие от {@link ScanProgressService}.
 * <p>
 * Метод вынесен в отдельный бин, потому что reaper вызывает {@link #failStuck} через прокси:
 * {@code @Transactional} self-invocation внутри reaper'а пошёл бы мимо прокси и потерял транзакцию
 * (тот самый dual-write).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanLifecycleService {

    private final ComplianceScanRepository scanRepository;
    private final ComplianceFindingRepository findingRepository;
    private final OutboxEventRepository outboxRepository;
    private final OutboxEventFactory outboxEventFactory;
    private final ComplianceWorkerProperties properties;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Transactional
    public void markCrawling(UUID scanId) {
        moveToIntermediate(scanId, ScanStatus.CRAWLING);
    }

    @Transactional
    public void markAnalyzing(UUID scanId) {
        moveToIntermediate(scanId, ScanStatus.ANALYZING);
    }

    @Transactional
    public void complete(UUID scanId, ScanResult result) {
        finalize(scanId, ScanStatus.COMPLETED, result);
    }

    @Transactional
    public void partial(UUID scanId, ScanResult result) {
        finalize(scanId, ScanStatus.PARTIAL, result);
    }

    @Transactional
    public void fail(UUID scanId, String errorMessage) {
        ComplianceScan scan = scanRepository.findById(scanId).orElseThrow();
        if (scan.getStatus().isTerminal()) {
            return;
        }
        scan.fail(errorMessage);
        markDuration(scan);
        recordDuration(scan);
        outboxRepository.save(scanFailedEvent(scan));
    }

    /** Вызывается reaper'ом (§5.3): no-op если статус уже терминальный. */
    @Transactional
    public void failStuck(UUID scanId, Duration staleAfter) {
        ComplianceScan scan = scanRepository.findById(scanId).orElseThrow();
        if (scan.getStatus().isTerminal()) {
            return; // уже завершён (живой листенер успел) — пропускаем
        }
        scan.fail("Scan timed out — no progress for " + staleAfter);
        markDuration(scan);
        recordDuration(scan);
        outboxRepository.save(scanFailedEvent(scan));
        log.info("Reaper failed stuck scan {} (was {})", scanId, scan.getStatus());
    }

    private void moveToIntermediate(UUID scanId, ScanStatus target) {
        ComplianceScan scan = scanRepository.findById(scanId).orElseThrow();
        if (scan.getStatus().isTerminal()) {
            log.debug("Skip {} for already-terminal scan {}", target, scanId);
            return;
        }
        if (scan.getStartedAt() == null) {
            scan.setStartedAt(Instant.now());
        }
        scan.setStatus(target);
    }

    private void finalize(UUID scanId, ScanStatus terminal, ScanResult result) {
        ComplianceScan scan = scanRepository.findById(scanId).orElseThrow();
        if (scan.getStatus().isTerminal()) {
            return;
        }
        findingRepository.deleteByScanId(scanId); // идемпотентность при перезапуске зависшего скана
        findingRepository.saveAll(result.findings());

        scan.setStatus(terminal);
        scan.setScore(result.score());
        scan.setPagesScanned(result.pagesFetched());
        scan.setDiagnosticsJson(result.diagnosticsJson());
        scan.setProgressPct(100);
        scan.setFinishedAt(Instant.now());
        markDuration(scan);
        recordDuration(scan);

        outboxRepository.save(scanCompletedEvent(scan, terminal, result));
    }

    /** Метрика длительности скана (§5.7): timer с тегами status/kind. */
    private void recordDuration(ComplianceScan scan) {
        if (scan.getDurationMs() == null) {
            return;
        }
        meterRegistry.timer("compliance.scan.duration",
                        "status", String.valueOf(scan.getStatus()),
                        "kind", String.valueOf(scan.getKind()))
                .record(java.time.Duration.ofMillis(scan.getDurationMs()));
    }

    private static void markDuration(ComplianceScan scan) {
        if (scan.getFinishedAt() == null) {
            scan.setFinishedAt(Instant.now());
        }
        if (scan.getStartedAt() != null) {
            scan.setDurationMs(Duration.between(scan.getStartedAt(), scan.getFinishedAt()).toMillis());
        }
    }

    private OutboxEvent scanCompletedEvent(ComplianceScan scan, ScanStatus status, ScanResult result) {
        ScanCompletedEvent event = new ScanCompletedEvent(
                UUID.randomUUID(), 1, scan.getId(), scan.getUserId(), scan.getGuestId(),
                status, result.score(), result.pagesFetched(), Instant.now());
        return outboxEventFactory.create(
                scan.getId(), properties.getKafka().getTopic().getScanCompleted(),
                scan.getId().toString(), event);
    }

    private OutboxEvent scanFailedEvent(ComplianceScan scan) {
        ScanFailedEvent event = new ScanFailedEvent(
                UUID.randomUUID(), 1, scan.getId(), scan.getUserId(), scan.getGuestId(),
                scan.getErrorMessage(), Instant.now());
        return outboxEventFactory.create(
                scan.getId(), properties.getKafka().getTopic().getScanFailed(),
                scan.getId().toString(), event);
    }
}
