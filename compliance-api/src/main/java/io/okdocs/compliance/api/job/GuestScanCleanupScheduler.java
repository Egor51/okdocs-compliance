package io.okdocs.compliance.api.job;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * TTL-чистка эфемерных FREE_MARKETING-сканов (§4.6): раз в день удаляет лид-магнит-сканы старше
 * {@code scan.freeMarketingRetentionDays}, <b>по {@code kind}</b> (не по {@code userId IS NULL}) —
 * после split FREE/PREMIUM (Этап 5.5) free-скан может принадлежать залогиненному юзеру, и гард по
 * владельцу его бы не удалил. Cascade удалит findings/emails. CABINET_PREMIUM хранится в истории
 * кабинета, чистке не подлежит.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuestScanCleanupScheduler {

    private final ComplianceScanRepository scanRepository;
    private final ComplianceApiProperties properties;

    @Scheduled(cron = "${compliance.scan.guest-cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void cleanupFreeMarketingScans() {
        int retentionDays = properties.scan().freeMarketingRetentionDays();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int removed = scanRepository.deleteByKindOlderThan(ScanKind.FREE_MARKETING, cutoff);
        if (removed > 0) {
            log.info("TTL-чистка: удалено {} FREE_MARKETING-сканов старше {} дней", removed, retentionDays);
        }
    }
}
