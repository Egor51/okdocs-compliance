package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Best-effort апдейтер прогресса (§5.3) — <b>отдельный</b> от {@link ScanLifecycleService} бин,
 * вне строгих переходов статуса. Двигает {@code progressPct}/{@code progressStep}/{@code updatedAt},
 * чтобы (а) прогресс-бар не врал, (б) reaper не считал живой долгий скан зависшим.
 * <p>
 * <b>Реализация через прямой {@code @Modifying} UPDATE</b>, а НЕ dirty-checking entity: при
 * обновлении загруженной сущности {@code OptimisticLockingFailureException} прилетел бы на
 * flush/commit — то есть <i>после</i> выхода из метода через {@code @Transactional}-proxy, где
 * внутренний try/catch его уже не поймает, и ошибка best-effort телеметрии завалила бы пайплайн.
 * Прямой UPDATE по {@code id} + гард по нетерминальному статусу не трогает {@code @Version}, не
 * конфликтует с lifecycle-переходами и не бросает OLE. Прогресс намеренно НЕ в lifecycle-сервисе:
 * смена статуса строгая (OLE = конфликт, пробросить), прогресс — телеметрия (потерять не страшно).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanProgressService {

    private final ComplianceScanRepository scanRepository;

    @Transactional
    public void updateProgress(UUID scanId, int pct, String step) {
        int clamped = Math.max(0, Math.min(100, pct));
        int updated = scanRepository.updateProgress(scanId, clamped, step, Instant.now());
        if (updated == 0) {
            // Скан уже терминальный/удалён — прогресс не двигаем. Не ошибка.
            log.debug("Progress update skipped for scan {} (terminal or gone)", scanId);
        }
    }
}
