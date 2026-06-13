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
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportSnapshotBackfillJobTest {

    @Mock ComplianceScanRepository scanRepository;
    @Mock ScanLifecycleService lifecycle;

    ComplianceWorkerProperties properties;
    ReportSnapshotBackfillJob job;

    @BeforeEach
    void setUp() {
        properties = new ComplianceWorkerProperties();
        properties.getBackfill().setReportsEnabled(true);
        properties.getBackfill().setBatchSize(10);
        properties.getBackfill().setMaxAttempts(2);
        job = new ReportSnapshotBackfillJob(scanRepository, lifecycle, properties);
    }

    @Test
    void disabled_noRepositoryCalls() {
        properties.getBackfill().setReportsEnabled(false);

        job.backfillReports();

        verifyNoInteractions(scanRepository, lifecycle);
    }

    @Test
    void poisonedScan_isSkippedAfterMaxAttemptsUntilRestart() {
        ComplianceScan poison = scan();
        when(scanRepository.findTerminalWithoutReport(any(), any(PageRequest.class)))
                .thenReturn(List.of(poison));
        doThrow(new IllegalStateException("bad diagnostics"))
                .when(lifecycle).backfillReportSnapshot(poison.getId());

        job.backfillReports();
        job.backfillReports();
        job.backfillReports();

        verify(lifecycle, times(2)).backfillReportSnapshot(poison.getId());
    }

    @Test
    void skippedPoison_doesNotBlockLaterScansInSameQueryWindow() {
        ComplianceScan poison = scan();
        ComplianceScan healthy = scan();
        when(scanRepository.findTerminalWithoutReport(any(), any(PageRequest.class)))
                .thenReturn(List.of(poison), List.of(poison), List.of(poison, healthy));
        doThrow(new IllegalStateException("bad diagnostics"))
                .when(lifecycle).backfillReportSnapshot(poison.getId());

        job.backfillReports();
        job.backfillReports();
        job.backfillReports();

        verify(lifecycle, times(2)).backfillReportSnapshot(poison.getId());
        verify(lifecycle).backfillReportSnapshot(healthy.getId());
    }

    @Test
    void emptyBatch_noLifecycleCalls() {
        when(scanRepository.findTerminalWithoutReport(
                eq(List.of(ScanStatus.COMPLETED, ScanStatus.PARTIAL)), any(PageRequest.class)))
                .thenReturn(List.of());

        job.backfillReports();

        verify(lifecycle, never()).backfillReportSnapshot(any());
    }

    private static ComplianceScan scan() {
        ComplianceScan scan = new ComplianceScan();
        scan.setId(UUID.randomUUID());
        scan.setStatus(ScanStatus.COMPLETED);
        return scan;
    }
}
