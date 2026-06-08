package io.okdocs.compliance.worker.it;

import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import io.okdocs.compliance.worker.service.ScanProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT {@link ScanProgressService}: best-effort прогресс двигает нетерминальный скан, но НЕ трогает
 * терминальный (гард {@code status NOT IN terminal} в bulk-UPDATE — иначе прогресс перезаписал бы
 * финальный результат или «оживил» завершённый скан).
 */
@SpringBootTest(classes = PersistenceItConfig.class)
class ScanProgressServiceIT extends AbstractPostgresIT {

    @Autowired
    ScanProgressService progressService;
    @Autowired
    ComplianceScanRepository scanRepository;

    @BeforeEach
    void clean() {
        scanRepository.deleteAll();
    }

    @Test
    void updateProgress_movesNonTerminalScan() {
        ComplianceScan scan = persist(ScanStatus.CRAWLING, 10, "Краулинг");

        progressService.updateProgress(scan.getId(), 60, "Анализ соответствия");

        ComplianceScan reloaded = scanRepository.findById(scan.getId()).orElseThrow();
        assertThat(reloaded.getProgressPct()).isEqualTo(60);
        assertThat(reloaded.getProgressStep()).isEqualTo("Анализ соответствия");
    }

    @Test
    void updateProgress_clampsOutOfRange() {
        ComplianceScan scan = persist(ScanStatus.ANALYZING, 50, "Анализ");

        progressService.updateProgress(scan.getId(), 250, "overflow");

        assertThat(scanRepository.findById(scan.getId()).orElseThrow().getProgressPct()).isEqualTo(100);
    }

    @Test
    void updateProgress_doesNotTouchTerminalScan() {
        // Завершённый скан: pct=100, финальный step. Поздний best-effort апдейт не должен его тронуть.
        ComplianceScan scan = persist(ScanStatus.COMPLETED, 100, "Готово");

        progressService.updateProgress(scan.getId(), 42, "поздний апдейт");

        ComplianceScan reloaded = scanRepository.findById(scan.getId()).orElseThrow();
        assertThat(reloaded.getProgressPct()).isEqualTo(100);
        assertThat(reloaded.getProgressStep()).isEqualTo("Готово");
    }

    @Test
    void updateProgress_doesNotTouchFailedScan() {
        ComplianceScan scan = persist(ScanStatus.FAILED, 30, "Упал");

        progressService.updateProgress(scan.getId(), 90, "поздний апдейт");

        ComplianceScan reloaded = scanRepository.findById(scan.getId()).orElseThrow();
        assertThat(reloaded.getProgressPct()).isEqualTo(30);
        assertThat(reloaded.getProgressStep()).isEqualTo("Упал");
    }

    private ComplianceScan persist(ScanStatus status, int pct, String step) {
        ComplianceScan scan = new ComplianceScan();
        scan.setId(UUID.randomUUID());
        scan.setGuestId(UUID.randomUUID()); // гость — без FK на app_users
        scan.setIpAddress("203.0.113.10");
        scan.setStatus(status);
        scan.setSiteUrl("https://example.com");
        scan.setSiteDomain("example.com");
        scan.setProgressStep(step);
        scan.setProgressPct(pct);
        scan.setTier(ScanTier.FREE);
        scan.setKind(ScanKind.CABINET_PREMIUM);
        scan.setJurisdiction(ScanJurisdiction.RU);
        scan.setMaxPages(30);
        scan.setDynamicRequired(false);
        return scanRepository.saveAndFlush(scan);
    }
}
