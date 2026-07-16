package io.okdocs.compliance.api.job;

import io.okdocs.compliance.api.service.SiteMonitorExecutionService;
import io.okdocs.compliance.contracts.exception.InsufficientScanBalanceException;
import io.okdocs.compliance.persistence.monitoring.SiteMonitor;
import io.okdocs.compliance.persistence.monitoring.SiteMonitorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/** Polls due site monitors and leases them safely across multiple API replicas. */
@Slf4j
@Component
public class SiteMonitorScheduler {

    private static final int BATCH_SIZE = 50;

    private final SiteMonitorRepository monitorRepository;
    private final SiteMonitorExecutionService executionService;
    private final TransactionOperations transactions;
    private final String instanceId = "site-monitor-" + UUID.randomUUID();

    @Autowired
    public SiteMonitorScheduler(SiteMonitorRepository monitorRepository,
                                SiteMonitorExecutionService executionService,
                                PlatformTransactionManager transactionManager) {
        this(monitorRepository, executionService, new TransactionTemplate(transactionManager));
    }

    SiteMonitorScheduler(SiteMonitorRepository monitorRepository,
                         SiteMonitorExecutionService executionService,
                         TransactionOperations transactions) {
        this.monitorRepository = monitorRepository;
        this.executionService = executionService;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${compliance.monitoring.poll-interval-ms:60000}")
    public void runDueMonitors() {
        UUID lockToken = UUID.randomUUID();
        List<SiteMonitor> batch = transactions.execute(status ->
                monitorRepository.claimDue(instanceId, lockToken, BATCH_SIZE));
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (SiteMonitor monitor : batch) {
            try {
                executionService.executeClaimed(monitor.getId(), lockToken);
            } catch (InsufficientScanBalanceException e) {
                executionService.pauseForNoBalance(monitor.getId(), lockToken);
            } catch (RuntimeException e) {
                log.error("Monitoring run failed before scan start: monitorId={}", monitor.getId(), e);
                executionService.releaseLease(monitor.getId(), lockToken);
            }
        }
    }
}
