package io.okdocs.compliance.worker.it;

import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.OutboxStatus;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.persistence.outbox.OutboxEvent;
import io.okdocs.compliance.persistence.outbox.OutboxEventRepository;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import io.okdocs.compliance.persistence.scan.ComplianceFindingRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import io.okdocs.compliance.worker.service.ScanLifecycleService;
import io.okdocs.compliance.worker.service.ScanResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * IT транзакционных переходов {@link ScanLifecycleService}: статус + findings + outbox пишутся в
 * <b>одной</b> транзакции (transactional outbox), терминальный статус не переоткрывается, а при
 * ошибке середины транзакции откатывается всё (атомарность).
 */
@SpringBootTest(classes = PersistenceItConfig.class)
class ScanLifecycleServiceIT extends AbstractPostgresIT {

    @Autowired
    ScanLifecycleService lifecycle;
    @Autowired
    ComplianceScanRepository scanRepository;
    @Autowired
    OutboxEventRepository outboxRepository;
    // Spy подменяет реальный бин findingRepository в контексте — можно и читать, и инъецировать сбой.
    @MockitoSpyBean
    ComplianceFindingRepository findingRepository;

    @BeforeEach
    void clean() {
        reset(findingRepository);
        findingRepository.deleteAll();
        outboxRepository.deleteAll();
        scanRepository.deleteAll();
    }

    @Test
    void complete_writesStatusFindingsAndOutbox_inOneTransaction() {
        ComplianceScan scan = persistQueuedScan();
        ScanResult result = new ScanResult(
                List.of(finding(scan.getId(), "RULE_A"), finding(scan.getId(), "RULE_B")), 73, 5, "{}");

        lifecycle.markCrawling(scan.getId());
        lifecycle.markAnalyzing(scan.getId());
        lifecycle.complete(scan.getId(), result);

        ComplianceScan reloaded = scanRepository.findById(scan.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(reloaded.getScore()).isEqualTo(73);
        assertThat(reloaded.getProgressPct()).isEqualTo(100);
        assertThat(findingRepository.findByScanIdOrderByCreatedAtAsc(scan.getId())).hasSize(2);

        List<OutboxEvent> outbox = outboxRepository.findAll();
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).getEventType()).isEqualTo("ScanCompletedEvent");
        assertThat(outbox.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.get(0).getAggregateId()).isEqualTo(scan.getId());
    }

    @Test
    void fail_writesFailedStatusAndScanFailedOutbox_noFindings() {
        ComplianceScan scan = persistQueuedScan();
        lifecycle.markCrawling(scan.getId());

        lifecycle.fail(scan.getId(), "boom");

        ComplianceScan reloaded = scanRepository.findById(scan.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ScanStatus.FAILED);
        assertThat(reloaded.getErrorMessage()).isEqualTo("boom");
        assertThat(findingRepository.findByScanIdOrderByCreatedAtAsc(scan.getId())).isEmpty();

        List<OutboxEvent> outbox = outboxRepository.findAll();
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).getEventType()).isEqualTo("ScanFailedEvent");
    }

    @Test
    void terminalScan_isNotReopened() {
        ComplianceScan scan = persistQueuedScan();
        lifecycle.complete(scan.getId(), new ScanResult(List.of(), 100, 1, "{}"));

        // Повторный complete/fail на терминальном — no-op, без второго outbox-события.
        lifecycle.fail(scan.getId(), "late failure");
        lifecycle.complete(scan.getId(), new ScanResult(List.of(), 0, 1, "{}"));

        ComplianceScan reloaded = scanRepository.findById(scan.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(reloaded.getScore()).isEqualTo(100);
        assertThat(outboxRepository.findAll()).hasSize(1); // только первый complete
    }

    @Test
    void complete_isAtomic_failureRollsBackStatusFindingsAndOutbox() {
        ComplianceScan scan = persistQueuedScan();
        lifecycle.markAnalyzing(scan.getId());

        // Сбой на середине транзакции (saveAll findings) должен откатить статус и outbox целиком.
        doThrow(new RuntimeException("db down")).when(findingRepository).saveAll(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> lifecycle.complete(scan.getId(),
                new ScanResult(List.of(finding(scan.getId(), "RULE_A")), 50, 3, "{}")))
                .isInstanceOf(RuntimeException.class);

        ComplianceScan reloaded = scanRepository.findById(scan.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ScanStatus.ANALYZING); // НЕ COMPLETED — откат
        assertThat(reloaded.getScore()).isNull();
        assertThat(findingRepository.findByScanIdOrderByCreatedAtAsc(scan.getId())).isEmpty();
        assertThat(outboxRepository.findAll()).isEmpty(); // outbox-событие тоже откатилось
    }

    private ComplianceScan persistQueuedScan() {
        ComplianceScan scan = new ComplianceScan();
        scan.setId(UUID.randomUUID());
        // Гостевой скан (guest_id без FK) — избегаем FK user_id → app_users в IT без сидинга юзеров.
        scan.setGuestId(UUID.randomUUID());
        scan.setIpAddress("203.0.113.10");
        scan.setStatus(ScanStatus.QUEUED);
        scan.setSiteUrl("https://example.com");
        scan.setSiteDomain("example.com");
        scan.setProgressStep("Ожидание");
        scan.setProgressPct(0);
        scan.setTier(ScanTier.FREE);
        scan.setKind(ScanKind.CABINET_PREMIUM);
        scan.setJurisdiction(ScanJurisdiction.RU);
        scan.setMaxPages(30);
        scan.setDynamicRequired(false);
        return scanRepository.saveAndFlush(scan);
    }

    private ComplianceFinding finding(UUID scanId, String code) {
        ComplianceFinding f = new ComplianceFinding();
        f.setId(UUID.randomUUID());
        f.setScanId(scanId);
        f.setCode(code);
        f.setSeverity(FindingSeverity.HIGH);
        f.setCategory(FindingCategory.CONSENT);
        f.setTitle("Test finding " + code);
        return f;
    }
}
