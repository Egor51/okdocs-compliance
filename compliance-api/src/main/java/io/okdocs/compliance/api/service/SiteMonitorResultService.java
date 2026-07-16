package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.enums.MonitorRunStatus;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.persistence.monitoring.MonitorRun;
import io.okdocs.compliance.persistence.monitoring.MonitorRunRepository;
import io.okdocs.compliance.persistence.monitoring.SiteMonitor;
import io.okdocs.compliance.persistence.monitoring.SiteMonitorRepository;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import io.okdocs.compliance.persistence.scan.ComplianceFindingRepository;
import io.okdocs.compliance.mail.config.ComplianceMailProperties;
import io.okdocs.compliance.mail.notification.MailNotificationService;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Finalizes monitoring history and calculates a stable finding-code/page diff. */
@Service
@RequiredArgsConstructor
public class SiteMonitorResultService {

    private final MonitorRunRepository runRepository;
    private final SiteMonitorRepository monitorRepository;
    private final ComplianceFindingRepository findingRepository;
    private final AppUserRepository userRepository;
    private final MailNotificationService mailNotificationService;
    private final ComplianceMailProperties mailProperties;

    @Transactional
    public void completed(UUID scanId, ScanStatus scanStatus, Integer score, Instant completedAt) {
        MonitorRun run = runRepository.findByScanId(scanId).orElse(null);
        if (run == null || run.getStatus() != MonitorRunStatus.RUNNING) {
            return;
        }
        SiteMonitor monitor = monitorRepository.findById(run.getMonitorId()).orElse(null);
        if (monitor == null) {
            return;
        }

        Diff diff = diff(monitor.getLastScanId(), scanId);
        run.setStatus(scanStatus == ScanStatus.PARTIAL
                ? MonitorRunStatus.PARTIAL : MonitorRunStatus.COMPLETED);
        run.setCurrentScore(score);
        run.setNewFindings(diff.added());
        run.setResolvedFindings(diff.resolved());
        run.setFinishedAt(completedAt);
        runRepository.save(run);

        monitor.setLastScanId(scanId);
        monitor.setLastScore(score);
        monitor.setLastRunAt(completedAt);
        monitorRepository.save(monitor);

        if (monitor.isNotificationsEnabled() && monitor.getLastScanId() != null
                && hasMeaningfulChange(run.getPreviousScore(), score, diff)) {
            userRepository.findById(monitor.getUserId()).ifPresent(user -> {
                String email = user.getEmail();
                if (email == null || email.isBlank()) {
                    return;
                }
                String locale = "en".equalsIgnoreCase(monitor.getLocale()) ? "en" : "ru";
                String reportUrl = mailProperties.frontendBaseUrl() + "/" + locale
                        + "/dashboard/reports/" + scanId;
                mailNotificationService.enqueueMonitoringAlert(
                        scanId, email, monitor.getSiteDomain(), run.getPreviousScore(), score,
                        diff.added(), diff.resolved(), reportUrl, locale);
            });
        }
    }

    @Transactional
    public void failed(UUID scanId, String errorMessage, Instant failedAt) {
        MonitorRun run = runRepository.findByScanId(scanId).orElse(null);
        if (run == null || run.getStatus() != MonitorRunStatus.RUNNING) {
            return;
        }
        run.setStatus(MonitorRunStatus.FAILED);
        run.setErrorMessage(errorMessage);
        run.setFinishedAt(failedAt);
        runRepository.save(run);
        monitorRepository.findById(run.getMonitorId()).ifPresent(monitor -> {
            monitor.setLastRunAt(failedAt);
            monitorRepository.save(monitor);
        });
    }

    private Diff diff(UUID previousScanId, UUID currentScanId) {
        if (previousScanId == null) {
            return new Diff(0, 0);
        }
        Set<String> previous = keys(previousScanId);
        Set<String> current = keys(currentScanId);
        Set<String> added = new HashSet<>(current);
        added.removeAll(previous);
        Set<String> resolved = new HashSet<>(previous);
        resolved.removeAll(current);
        return new Diff(added.size(), resolved.size());
    }

    private static boolean hasMeaningfulChange(Integer previousScore, Integer currentScore, Diff diff) {
        return diff.added() > 0 || diff.resolved() > 0
                || (previousScore != null && currentScore != null && !previousScore.equals(currentScore));
    }

    private Set<String> keys(UUID scanId) {
        if (scanId == null) {
            return Set.of();
        }
        List<ComplianceFinding> findings = findingRepository.findByScanIdOrderByCreatedAtAsc(scanId);
        Set<String> keys = new HashSet<>();
        for (ComplianceFinding finding : findings) {
            String page = finding.getPageUrl() != null ? finding.getPageUrl() : finding.getSourceUrl();
            keys.add(finding.getCode() + "|" + (page == null ? "" : page.trim().toLowerCase()));
        }
        return keys;
    }

    private record Diff(int added, int resolved) {
    }
}
