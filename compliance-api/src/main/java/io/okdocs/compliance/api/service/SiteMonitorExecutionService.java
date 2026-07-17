package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.cabinet.ScanBalanceDto;
import io.okdocs.compliance.contracts.enums.MonitorRunStatus;
import io.okdocs.compliance.contracts.enums.MonitorTrigger;
import io.okdocs.compliance.contracts.enums.SiteMonitorStatus;
import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.exception.InsufficientScanBalanceException;
import io.okdocs.compliance.contracts.exception.SiteMonitorNotFoundException;
import io.okdocs.compliance.contracts.scan.ScanStatusResponse;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.monitoring.MonitorRun;
import io.okdocs.compliance.persistence.monitoring.MonitorRunRepository;
import io.okdocs.compliance.persistence.monitoring.SiteMonitor;
import io.okdocs.compliance.persistence.monitoring.SiteMonitorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

/** Transaction boundary for exactly one monitoring execution. */
@Service
@RequiredArgsConstructor
public class SiteMonitorExecutionService {

    private final SiteMonitorRepository monitorRepository;
    private final MonitorRunRepository runRepository;
    private final AppUserRepository userRepository;
    private final ScanBalanceService balanceService;
    private final ScanCommandService scanCommandService;

    @Transactional
    public void executeClaimed(UUID monitorId, UUID lockToken) {
        SiteMonitor monitor = monitorRepository.findById(monitorId)
                .orElseThrow(() -> new SiteMonitorNotFoundException(monitorId));
        if (!lockToken.equals(monitor.getLockToken())) {
            return;
        }
        if (monitor.getStatus() != SiteMonitorStatus.ACTIVE) {
            monitorRepository.releaseLease(monitorId, lockToken);
            return;
        }

        Instant scheduledFor = monitor.getNextRunAt();
        Instant nextRunAt = nextScheduled(scheduledFor, monitor.getIntervalDays(),
                ZoneId.of(monitor.getTimezone()), Instant.now());
        AppUser user = userRepository.findById(monitor.getUserId()).orElse(null);
        if (!hasActivePaidPlan(user, Instant.now())) {
            recordSkipped(monitor, scheduledFor, "PLAN_EXPIRED");
            finishClaim(monitorId, lockToken, SiteMonitorStatus.PAUSED_PLAN_EXPIRED, nextRunAt);
            return;
        }

        ScanBalanceDto balance = balanceService.getBalance(monitor.getUserId());
        if (balance.available() <= 0) {
            recordSkipped(monitor, scheduledFor, "NO_BALANCE");
            finishClaim(monitorId, lockToken, SiteMonitorStatus.PAUSED_NO_BALANCE, nextRunAt);
            return;
        }
        if (runRepository.existsByMonitorIdAndStatus(monitorId, MonitorRunStatus.RUNNING)) {
            recordSkipped(monitor, scheduledFor, "SCAN_ALREADY_RUNNING");
            finishClaim(monitorId, lockToken, SiteMonitorStatus.ACTIVE, nextRunAt);
            return;
        }

        ScanStatusResponse scan = scanCommandService.startMonitoringScan(
                monitor.getUserId(), monitor.getId(), monitor.getSiteUrl(), monitor.getJurisdiction(),
                monitor.getLocale(), monitor.getLastScanId());
        MonitorRun run = new MonitorRun();
        run.setMonitorId(monitorId);
        run.setScanId(scan.id());
        run.setTrigger(MonitorTrigger.SCHEDULE);
        run.setStatus(MonitorRunStatus.RUNNING);
        run.setScheduledFor(scheduledFor);
        run.setPreviousScore(monitor.getLastScore());
        runRepository.save(run);
        finishClaim(monitorId, lockToken, SiteMonitorStatus.ACTIVE, nextRunAt);
    }

    @Transactional
    public void executeManual(UUID monitorId, Long userId) {
        SiteMonitor monitor = monitorRepository.findByIdAndUserId(monitorId, userId)
                .orElseThrow(() -> new SiteMonitorNotFoundException(monitorId));
        AppUser user = userRepository.findById(userId).orElse(null);
        if (!hasActivePaidPlan(user, Instant.now())) {
            throw new ComplianceValidationException(
                    "Мониторинг доступен только при активном тарифе PRO или BUSINESS");
        }
        if (runRepository.existsByMonitorIdAndStatus(monitorId, MonitorRunStatus.RUNNING)) {
            throw new ComplianceValidationException("Проверка этого сайта уже выполняется");
        }

        ScanStatusResponse scan = scanCommandService.startMonitoringScan(
                userId, monitorId, monitor.getSiteUrl(), monitor.getJurisdiction(),
                monitor.getLocale(), monitor.getLastScanId());
        MonitorRun run = new MonitorRun();
        run.setMonitorId(monitorId);
        run.setScanId(scan.id());
        run.setTrigger(MonitorTrigger.MANUAL);
        run.setStatus(MonitorRunStatus.RUNNING);
        run.setScheduledFor(Instant.now());
        run.setPreviousScore(monitor.getLastScore());
        runRepository.save(run);
        if (monitor.getStatus() == SiteMonitorStatus.PAUSED_NO_BALANCE
                || monitor.getStatus() == SiteMonitorStatus.PAUSED_PLAN_EXPIRED) {
            monitor.setStatus(SiteMonitorStatus.ACTIVE);
            monitor.setNextRunAt(nextScheduled(Instant.now(), monitor.getIntervalDays(),
                    ZoneId.of(monitor.getTimezone()), Instant.now()));
            monitorRepository.save(monitor);
        }
    }

    /** Called after a debit race lost to another concurrent user action. */
    @Transactional
    public void pauseForNoBalance(UUID monitorId, UUID lockToken) {
        SiteMonitor monitor = monitorRepository.findById(monitorId).orElse(null);
        if (monitor == null || !lockToken.equals(monitor.getLockToken())) {
            return;
        }
        Instant scheduledFor = monitor.getNextRunAt();
        recordSkipped(monitor, scheduledFor, "NO_BALANCE");
        finishClaim(monitorId, lockToken, SiteMonitorStatus.PAUSED_NO_BALANCE,
                nextScheduled(scheduledFor, monitor.getIntervalDays(),
                        ZoneId.of(monitor.getTimezone()), Instant.now()));
    }

    @Transactional
    public void releaseLease(UUID monitorId, UUID lockToken) {
        monitorRepository.releaseLease(monitorId, lockToken);
    }

    private void recordSkipped(SiteMonitor monitor, Instant scheduledFor, String reason) {
        MonitorRun run = new MonitorRun();
        run.setMonitorId(monitor.getId());
        run.setTrigger(MonitorTrigger.SCHEDULE);
        run.setStatus(MonitorRunStatus.SKIPPED);
        run.setScheduledFor(scheduledFor);
        run.setPreviousScore(monitor.getLastScore());
        run.setErrorMessage(reason);
        run.setFinishedAt(Instant.now());
        runRepository.save(run);
    }

    private void finishClaim(UUID monitorId, UUID lockToken, SiteMonitorStatus status,
                             Instant nextRunAt) {
        if (monitorRepository.finishClaim(monitorId, lockToken, status, nextRunAt) != 1) {
            throw new IllegalStateException("Monitoring lease was lost for " + monitorId);
        }
    }

    static boolean hasActivePaidPlan(AppUser user, Instant now) {
        return user != null && user.getPlan() != UserPlan.FREE && user.getPlanRenewsAt() != null
                && user.getPlanRenewsAt().isAfter(now);
    }

    static Instant nextScheduled(Instant scheduledFor, int intervalDays, ZoneId zone, Instant now) {
        ZonedDateTime next = ZonedDateTime.ofInstant(scheduledFor, zone).plusDays(intervalDays);
        while (!next.toInstant().isAfter(now)) {
            next = next.plusDays(intervalDays);
        }
        return next.toInstant();
    }
}
