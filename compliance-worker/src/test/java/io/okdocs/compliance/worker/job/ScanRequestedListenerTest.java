package io.okdocs.compliance.worker.job;

import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.event.ScanRequestedEvent;
import io.okdocs.compliance.contracts.scan.ScanFailure;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import io.okdocs.compliance.worker.service.ScanLifecycleService;
import io.okdocs.compliance.worker.service.ScanPipeline;
import io.okdocs.compliance.worker.service.ScanResult;
import io.okdocs.compliance.worker.service.ScanFailures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Failure-semantics главного consumer'а (§5.2): idempotency-гард + правила ack/nack. Покрывает
 * матрицу из javadoc {@link ScanRequestedListener} и поведение при сбое пайплайна.
 */
@ExtendWith(MockitoExtension.class)
class ScanRequestedListenerTest {

    @Mock ComplianceScanRepository scanRepository;
    @Mock ScanPipeline pipeline;
    @Mock ScanLifecycleService lifecycle;
    @Mock Acknowledgment ack;

    ComplianceWorkerProperties properties;
    ScanRequestedListener listener;

    private static final Duration REDELIVER = Duration.ofSeconds(30);
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    @BeforeEach
    void setUp() {
        properties = new ComplianceWorkerProperties();
        properties.getScan().setRedeliverDelay(REDELIVER);
        properties.getScan().setStaleAfter(STALE_AFTER);
        listener = new ScanRequestedListener(scanRepository, pipeline, lifecycle, properties,
                new io.okdocs.compliance.worker.config.WorkerMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @Test
    void unknownScan_isAcked_notProcessed() {
        UUID scanId = UUID.randomUUID();
        when(scanRepository.findById(scanId)).thenReturn(Optional.empty());

        listener.onScanRequested(event(scanId), ack);

        verify(ack).acknowledge();
        verifyNoInteractions(pipeline, lifecycle);
    }

    @Test
    void terminalScan_duplicate_isAckedAndDropped() {
        // at-least-once Kafka: дубликат уже завершённого скана надо ПОГЛОТИТЬ (ack), иначе вечная передоставка.
        ComplianceScan scan = scan(ScanStatus.COMPLETED, Instant.now());
        when(scanRepository.findById(scan.getId())).thenReturn(Optional.of(scan));

        listener.onScanRequested(event(scan.getId()), ack);

        verify(ack).acknowledge();
        verifyNoInteractions(pipeline, lifecycle);
    }

    @Test
    void freshInProgressScan_isAcked_notRestarted() {
        ComplianceScan scan = scan(ScanStatus.CRAWLING, Instant.now()); // свежий
        when(scanRepository.findById(scan.getId())).thenReturn(Optional.of(scan));

        listener.onScanRequested(event(scan.getId()), ack);

        verify(ack).acknowledge();
        verify(ack, never()).nack(any());
        verifyNoInteractions(pipeline, lifecycle);
    }

    @Test
    void staleInProgressScan_isAcked_notRestarted() {
        // Redelivery never restarts an in-flight scan; reaper owns dead-worker recovery.
        ComplianceScan scan = scan(ScanStatus.ANALYZING, Instant.now().minus(STALE_AFTER).minusSeconds(60));
        when(scanRepository.findById(scan.getId())).thenReturn(Optional.of(scan));

        listener.onScanRequested(event(scan.getId()), ack);

        verify(ack).acknowledge();
        verify(ack, never()).nack(any());
        verifyNoInteractions(pipeline, lifecycle);
    }

    @Test
    void queuedScan_claimLost_isAcked_notProcessed() {
        ComplianceScan scan = scan(ScanStatus.QUEUED, Instant.now());
        when(scanRepository.findById(scan.getId())).thenReturn(Optional.of(scan));
        when(lifecycle.claimForProcessing(scan.getId())).thenReturn(false);

        listener.onScanRequested(event(scan.getId()), ack);

        verify(ack).acknowledge();
        verifyNoInteractions(pipeline);
    }

    @Test
    void queuedScan_completed_marksLifecycleAndAcks() {
        ComplianceScan scan = scan(ScanStatus.QUEUED, Instant.now());
        when(scanRepository.findById(scan.getId())).thenReturn(Optional.of(scan));
        when(lifecycle.claimForProcessing(scan.getId())).thenReturn(true);
        when(pipeline.run(eq(scan), eq(scan.getId())))
                .thenReturn(ScanPipeline.PipelineOutcome.of(ScanStatus.COMPLETED,
                        new ScanResult(List.of(), 80, 5, "{}")));

        listener.onScanRequested(event(scan.getId()), ack);

        verify(lifecycle).claimForProcessing(scan.getId());
        verify(lifecycle).markAnalyzing(scan.getId());
        verify(lifecycle).complete(eq(scan.getId()), any());
        verify(ack).acknowledge();
    }

    @Test
    void pipelineReturnsFailed_marksFail_andAcks() {
        // 0 страниц / dynamic-required без CDP → FAILED-исход пайплайна: fail + ack (refund по событию).
        ComplianceScan scan = scan(ScanStatus.QUEUED, Instant.now());
        when(scanRepository.findById(scan.getId())).thenReturn(Optional.of(scan));
        when(lifecycle.claimForProcessing(scan.getId())).thenReturn(true);
        ScanFailure failure = ScanFailures.noPages();
        when(pipeline.run(eq(scan), eq(scan.getId())))
                .thenReturn(ScanPipeline.PipelineOutcome.failed(failure));

        listener.onScanRequested(event(scan.getId()), ack);

        verify(lifecycle).fail(eq(scan.getId()), eq(failure));
        verify(lifecycle, never()).complete(any(), any());
        verify(ack).acknowledge();
    }

    @Test
    void pipelineThrows_committedFailThenAck() {
        // Worker падает во время crawl: финальный fail коммитим (+ ScanFailedEvent через outbox), затем
        // ack — иначе бесконечный redelivery упавшего скана.
        ComplianceScan scan = scan(ScanStatus.QUEUED, Instant.now());
        when(scanRepository.findById(scan.getId())).thenReturn(Optional.of(scan));
        when(lifecycle.claimForProcessing(scan.getId())).thenReturn(true);
        when(pipeline.run(eq(scan), eq(scan.getId()))).thenThrow(new RuntimeException("crawl boom"));

        listener.onScanRequested(event(scan.getId()), ack);

        verify(lifecycle).fail(eq(scan.getId()), any(ScanFailure.class));
        verify(ack).acknowledge();
        verify(ack, never()).nack(any());
    }

    @Test
    void pipelineThrows_andFailAlsoThrows_nackForManagedRedelivery() {
        // Даже FAILED не закоммитился (БД недоступна) → nack, reaper подстрахует по staleAfter.
        ComplianceScan scan = scan(ScanStatus.QUEUED, Instant.now());
        when(scanRepository.findById(scan.getId())).thenReturn(Optional.of(scan));
        when(lifecycle.claimForProcessing(scan.getId())).thenReturn(true);
        when(pipeline.run(eq(scan), eq(scan.getId()))).thenThrow(new RuntimeException("crawl boom"));
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(lifecycle).fail(eq(scan.getId()), any(ScanFailure.class));

        listener.onScanRequested(event(scan.getId()), ack);

        verify(ack).nack(REDELIVER);
        verify(ack, never()).acknowledge();
    }

    private ScanRequestedEvent event(UUID scanId) {
        return new ScanRequestedEvent(UUID.randomUUID(), 1, scanId, 1L, null,
                "https://example.com", Instant.now());
    }

    private ComplianceScan scan(ScanStatus status, Instant updatedAt) {
        ComplianceScan scan = new ComplianceScan();
        scan.setId(UUID.randomUUID());
        scan.setUserId(1L);
        scan.setStatus(status);
        scan.setSiteUrl("https://example.com");
        scan.setSiteDomain("example.com");
        scan.setTier(ScanTier.FREE);
        scan.setKind(ScanKind.CABINET_PREMIUM);
        scan.setMaxPages(30);
        scan.setUpdatedAt(updatedAt);
        return scan;
    }
}
