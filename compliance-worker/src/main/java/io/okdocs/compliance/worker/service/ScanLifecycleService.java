package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.event.ScanCompletedEvent;
import io.okdocs.compliance.contracts.event.ScanFailedEvent;
import io.okdocs.compliance.contracts.scan.ScanFailure;
import io.okdocs.compliance.messaging.OutboxEventFactory;
import io.okdocs.compliance.persistence.outbox.OutboxEvent;
import io.okdocs.compliance.persistence.outbox.OutboxEventRepository;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import io.okdocs.compliance.persistence.scan.ComplianceFindingRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanReport;
import io.okdocs.compliance.persistence.scan.ComplianceScanReportRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private final ComplianceScanReportRepository scanReportRepository;
    private final ScanReportBuilder scanReportBuilder;
    private final OutboxEventRepository outboxRepository;
    private final OutboxEventFactory outboxEventFactory;
    private final ComplianceWorkerProperties properties;
    private final io.okdocs.compliance.worker.config.WorkerMetrics metrics;

    @Transactional
    public void markCrawling(UUID scanId) {
        moveToIntermediate(scanId, ScanStatus.CRAWLING);
    }

    /** Atomic QUEUED → CRAWLING claim used as the execution ownership boundary. */
    @Transactional
    public boolean claimForProcessing(UUID scanId) {
        return scanRepository.claimQueued(scanId, Instant.now()) == 1;
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
    public void fail(UUID scanId, ScanFailure failure) {
        ComplianceScan scan = scanRepository.findById(scanId).orElseThrow();
        if (scan.getStatus().isTerminal()) {
            return;
        }
        scan.fail(ScanFailures.legacyMessage(failure), failure);
        markDuration(scan);
        recordTerminal(scan);
        outboxRepository.save(scanFailedEvent(scan));
    }

    /** Вызывается reaper'ом (§5.3): no-op если статус уже терминальный. */
    @Transactional
    public void failStuck(UUID scanId, Duration staleAfter) {
        ComplianceScan scan = scanRepository.findByIdForUpdate(scanId).orElseThrow();
        if (scan.getStatus().isTerminal()) {
            return; // уже завершён (живой листенер успел) — пропускаем
        }
        Instant cutoff = Instant.now().minus(staleAfter);
        if (scan.getUpdatedAt() != null && !scan.getUpdatedAt().isBefore(cutoff)) {
            log.debug("Reaper skip scan {}: progress was refreshed after candidate selection", scanId);
            return;
        }
        ScanFailure failure = ScanFailures.pipelineTimeout();
        scan.fail(ScanFailures.legacyMessage(failure), failure);
        markDuration(scan);
        recordTerminal(scan);
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
        List<ComplianceFinding> findings = result.findings();
        findingRepository.saveAll(findings);

        scan.setStatus(terminal);
        scan.setScore(result.score());
        scan.setPagesScanned(result.pagesFetched());
        scan.setDiagnosticsJson(result.diagnosticsJson());
        scan.setProgressPct(100);
        scan.setFinishedAt(Instant.now());
        markDuration(scan);
        recordTerminal(scan);

        // Снапшот отчёта строим из УЖЕ финализированного scan (status/finishedAt/durationMs выставлены
        // выше) + тех же findings, что сохранили — не перечитывая из БД. Та же транзакция: если
        // сериализация упадёт, весь finalize откатится, скан не завершится наполовину.
        saveReportSnapshot(scan, findings);

        outboxRepository.save(scanCompletedEvent(scan, terminal, result));
    }

    /**
     * Backfill снапшота для уже завершённого скана (этап 3.5): читает findings из БД и строит/сохраняет
     * snapshot, НЕ трогая статус/outbox/findings — отчёт уже финализирован, меняем только наличие
     * снапшота. Идемпотентен (overwriting upsert) и в своей транзакции на скан (как {@link #failStuck}):
     * сбой одного не валит пачку. Вызывается backfill-джобом через прокси.
     */
    @Transactional
    public void backfillReportSnapshot(UUID scanId) {
        ComplianceScan scan = scanRepository.findById(scanId).orElseThrow();
        if (scan.getStatus() != ScanStatus.COMPLETED && scan.getStatus() != ScanStatus.PARTIAL) {
            log.debug("Skip report snapshot backfill for non-terminal scan {} with status {}",
                    scanId, scan.getStatus());
            return;
        }
        List<ComplianceFinding> findings = findingRepository.findByScanIdOrderByCreatedAtAsc(scanId);
        saveReportSnapshot(scan, findings);
    }

    /**
     * Перезаписывающий upsert снапшота: при перезапуске зависшего скана старый snapshot полностью
     * заменяется новым — та же явная семантика, что {@code findingRepository.deleteByScanId} выше,
     * без опоры на JPA merge по PK. {@code flush} перед {@code save} гарантирует порядок DELETE→INSERT.
     */
    private void saveReportSnapshot(ComplianceScan scan, List<ComplianceFinding> findings) {
        ScanReportSnapshots snapshots = scanReportBuilder.build(scan, findings);
        scanReportRepository.deleteById(scan.getId());
        scanReportRepository.flush();

        ComplianceScanReport report = new ComplianceScanReport();
        report.setScanId(scan.getId());
        report.setPremiumReportJson(snapshots.premiumJson());
        report.setFreeReportJson(snapshots.freeJson());
        scanReportRepository.save(report);
    }

    /**
     * Метрики терминального скана (§5.7): длительность (timer status/kind) + исход
     * (counter kind/jurisdiction/status — главный SLO-срез free vs premium). Зовётся из
     * complete/partial/fail/failStuck — единственная точка, где статус уже терминальный.
     */
    private void recordTerminal(ComplianceScan scan) {
        metrics.scanOutcome(scan.getKind(), scan.getJurisdiction(), scan.getStatus());
        if (scan.getDurationMs() != null) {
            metrics.recordScanDuration(scan.getStatus(), scan.getKind(), scan.getDurationMs());
        }
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
                UUID.randomUUID(), 2, scan.getId(), scan.getUserId(), scan.getGuestId(),
                scan.getErrorMessage(), scan.failure(), Instant.now());
        return outboxEventFactory.create(
                scan.getId(), properties.getKafka().getTopic().getScanFailed(),
                scan.getId().toString(), event, event.schemaVersion());
    }
}
