package io.okdocs.compliance.api.job;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * TTL-чистка эфемерных гостевых сканов (§4.6): раз в день удаляет сканы без владельца
 * ({@code userId IS NULL}) старше {@code scan.guestRetentionDays}. Cascade удалит findings/emails.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuestScanCleanupScheduler {

    private final ComplianceScanRepository scanRepository;
    private final ComplianceApiProperties properties;

    @Scheduled(cron = "${compliance.scan.guest-cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void cleanupGuestScans() {
        int retentionDays = properties.scan().guestRetentionDays();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int removed = scanRepository.deleteGuestScansOlderThan(cutoff);
        if (removed > 0) {
            log.info("TTL-чистка: удалено {} гостевых сканов старше {} дней", removed, retentionDays);
        }
    }
}
