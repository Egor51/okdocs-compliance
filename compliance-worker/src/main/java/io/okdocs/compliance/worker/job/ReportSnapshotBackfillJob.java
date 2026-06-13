package io.okdocs.compliance.worker.job;

import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import io.okdocs.compliance.worker.service.ScanLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Разовый backfill отчётных снапшотов (этап 3.5 переноса сборки отчёта в worker). После того как
 * новые сканы стали получать snapshot в {@link ScanLifecycleService#finalize}, старые terminal-сканы
 * ({@code COMPLETED}/{@code PARTIAL}) остаются без строки в {@code compliance_scan_reports}. Этот
 * джоб дозаполняет снапшоты, чтобы API мог оставаться passthrough-слоем без доменной сборки отчёта.
 * <p>
 * Включается флагом {@code compliance.backfill.reports-enabled} только на время миграции. Идёт
 * пачками: каждый скан бэкафиллится в своей транзакции ({@link ScanLifecycleService#backfillReportSnapshot}),
 * сбой одного не валит остальных. Когда пачка пуста — миграция завершена, джоб делает no-op (можно
 * выключить флаг). {@code FAILED} не бэкафиллится — у него нет findings и отчёт не строится.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportSnapshotBackfillJob {

    private static final List<ScanStatus> WITH_FINDINGS = List.of(ScanStatus.COMPLETED, ScanStatus.PARTIAL);

    private final ComplianceScanRepository scanRepository;
    private final ScanLifecycleService lifecycle;
    private final ComplianceWorkerProperties properties;
    private final ConcurrentHashMap<UUID, Integer> failuresByScanId = new ConcurrentHashMap<>();
    private final Set<UUID> skippedScanIds = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelayString = "${compliance.backfill.fixed-delay-ms:30000}")
    public void backfillReports() {
        if (!properties.getBackfill().isReportsEnabled()) {
            return;
        }
        int batchSize = properties.getBackfill().getBatchSize();
        List<ComplianceScan> batch = nextBatch(batchSize);
        if (batch.isEmpty()) {
            if (skippedScanIds.isEmpty()) {
                log.debug("Report backfill: nothing left — migration complete, flag can be disabled");
            } else {
                log.warn("Report backfill: no runnable scans in batch; {} scans are skipped until worker restart",
                        skippedScanIds.size());
            }
            return;
        }
        int ok = 0;
        int failed = 0;
        for (ComplianceScan scan : batch) {
            try {
                lifecycle.backfillReportSnapshot(scan.getId()); // своя транзакция на скан
                failuresByScanId.remove(scan.getId());
                ok++;
            } catch (RuntimeException e) {
                failed++;
                recordFailure(scan.getId());
                log.warn("Report backfill failed for scan {}: {}", scan.getId(), e.toString());
            }
        }
        log.info("Report backfill: {} ok, {} failed in this batch of {}", ok, failed, batch.size());
    }

    private List<ComplianceScan> nextBatch(int batchSize) {
        int querySize = Math.max(batchSize, batchSize + skippedScanIds.size());
        return scanRepository.findTerminalWithoutReport(WITH_FINDINGS, PageRequest.of(0, querySize)).stream()
                .filter(scan -> !skippedScanIds.contains(scan.getId()))
                .limit(batchSize)
                .toList();
    }

    private void recordFailure(UUID scanId) {
        int attempts = failuresByScanId.merge(scanId, 1, Integer::sum);
        int maxAttempts = properties.getBackfill().getMaxAttempts();
        if (attempts >= maxAttempts && skippedScanIds.add(scanId)) {
            log.warn("Report backfill: scan {} failed {} times, skipping it until worker restart",
                    scanId, attempts);
        }
    }
}
