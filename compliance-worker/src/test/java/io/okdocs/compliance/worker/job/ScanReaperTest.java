package io.okdocs.compliance.worker.job;

import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import io.okdocs.compliance.worker.service.ScanLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ScanReaper}: добивает зависшие in-progress сканы через {@link ScanLifecycleService#failStuck},
 * но не падает в гонке с ожившим сканом (OLE проглатывается — это «не наш случай», failStuck отдельной
 * транзакцией сам no-op'ит терминальный статус).
 */
@ExtendWith(MockitoExtension.class)
class ScanReaperTest {

    @Mock ComplianceScanRepository scanRepository;
    @Mock ScanLifecycleService lifecycle;

    ComplianceWorkerProperties properties;
    ScanReaper reaper;

    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    @BeforeEach
    void setUp() {
        properties = new ComplianceWorkerProperties();
        properties.getScan().setStaleAfter(STALE_AFTER);
        reaper = new ScanReaper(scanRepository, lifecycle, properties,
                new io.okdocs.compliance.worker.config.WorkerMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @Test
    void noStuckScans_noFailStuckCalls() {
        when(scanRepository.findByStatusInAndUpdatedAtBefore(anyList(), any())).thenReturn(List.of());

        reaper.reapStuckScans();

        verifyNoInteractions(lifecycle);
    }

    @Test
    void stuckScans_areFailedViaLifecycle() {
        ComplianceScan a = stuck();
        ComplianceScan b = stuck();
        when(scanRepository.findByStatusInAndUpdatedAtBefore(anyList(), any())).thenReturn(List.of(a, b));

        reaper.reapStuckScans();

        verify(lifecycle).failStuck(a.getId(), STALE_AFTER);
        verify(lifecycle).failStuck(b.getId(), STALE_AFTER);
    }

    @Test
    void scanRevivedConcurrently_OLE_isSwallowed_andOthersStillProcessed() {
        // Гонка с живым листенером: failStuck первого бросает OLE (version сдвинулся) — reaper не падает,
        // продолжает со вторым.
        ComplianceScan revived = stuck();
        ComplianceScan other = stuck();
        when(scanRepository.findByStatusInAndUpdatedAtBefore(anyList(), any()))
                .thenReturn(List.of(revived, other));
        doThrow(new OptimisticLockingFailureException("revived"))
                .when(lifecycle).failStuck(eq(revived.getId()), any());

        reaper.reapStuckScans();

        verify(lifecycle).failStuck(revived.getId(), STALE_AFTER);
        verify(lifecycle).failStuck(other.getId(), STALE_AFTER); // не прервались на OLE первого
    }

    @Test
    void cutoffIsStaleAfterInThePast() {
        // Гард «reaper не убивает живой скан»: выборка идёт по updatedAt < now - staleAfter.
        when(scanRepository.findByStatusInAndUpdatedAtBefore(
                eq(List.of(ScanStatus.CRAWLING, ScanStatus.ANALYZING)),
                any(Instant.class))).thenReturn(List.of());

        reaper.reapStuckScans();

        verify(scanRepository).findByStatusInAndUpdatedAtBefore(
                eq(List.of(ScanStatus.CRAWLING, ScanStatus.ANALYZING)), any(Instant.class));
        verify(lifecycle, never()).failStuck(any(), any());
    }

    private ComplianceScan stuck() {
        ComplianceScan scan = new ComplianceScan();
        scan.setId(UUID.randomUUID());
        scan.setStatus(ScanStatus.CRAWLING);
        return scan;
    }
}
