package io.okdocs.compliance.worker.job;

import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import io.okdocs.compliance.worker.service.ScanLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Страховка от навсегда зависших сканов (§5.3). Закрывает сценарий, где idempotency-гард листенера
 * сам по себе недостаточен: сообщение за-acknowledge-но, но коммит статуса откатился, либо ретеншн
 * Kafka истёк — передоставки не будет, скан застрял в {@code CRAWLING}/{@code ANALYZING}.
 * <p>
 * Reaper только решает «кого добивать»; транзакционный переход делегирован
 * {@link ScanLifecycleService#failStuck} (отдельный бин — вызов через прокси, иначе
 * {@code @Transactional} self-invocation потерял бы транзакцию).
 * <p>
 * {@code staleAfter} строго больше total-таймаута краулера (90s) — иначе reaper убил бы живой скан.
 * {@code updatedAt} двигает периодическая запись прогресса
 * ({@link io.okdocs.compliance.worker.service.ScanProgressService}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanReaper {

    private static final List<ScanStatus> IN_PROGRESS = List.of(ScanStatus.CRAWLING, ScanStatus.ANALYZING);

    private final ComplianceScanRepository scanRepository;
    private final ScanLifecycleService lifecycle;
    private final ComplianceWorkerProperties properties;
    private final io.okdocs.compliance.worker.config.WorkerMetrics metrics;

    @Scheduled(fixedDelay = 60_000)
    public void reapStuckScans() {
        Duration staleAfter = properties.getScan().getStaleAfter();
        Instant cutoff = Instant.now().minus(staleAfter);
        List<ComplianceScan> stuck = scanRepository.findByStatusInAndUpdatedAtBefore(IN_PROGRESS, cutoff);
        if (stuck.isEmpty()) {
            return;
        }
        log.info("Reaper found {} stuck scan(s) older than {}", stuck.size(), staleAfter);
        for (ComplianceScan scan : stuck) {
            try {
                lifecycle.failStuck(scan.getId(), staleAfter); // отдельная транзакция на скан
                metrics.reaperFailed();
            } catch (OptimisticLockingFailureException ignored) {
                // скан ожил между выборкой и апдейтом (живой листенер двинул version) — не наш случай
                log.debug("Stuck scan {} revived concurrently — skipping", scan.getId());
            }
        }
    }
}
