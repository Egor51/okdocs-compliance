package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.MonitorRunStatus;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.mail.config.ComplianceMailProperties;
import io.okdocs.compliance.mail.notification.MailNotificationService;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.monitoring.MonitorRun;
import io.okdocs.compliance.persistence.monitoring.MonitorRunRepository;
import io.okdocs.compliance.persistence.monitoring.SiteMonitor;
import io.okdocs.compliance.persistence.monitoring.SiteMonitorRepository;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import io.okdocs.compliance.persistence.scan.ComplianceFindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteMonitorResultServiceTest {

    @Mock private MonitorRunRepository runRepository;
    @Mock private SiteMonitorRepository monitorRepository;
    @Mock private ComplianceFindingRepository findingRepository;
    @Mock private AppUserRepository userRepository;
    @Mock private MailNotificationService mail;
    @Mock private ComplianceMailProperties mailProperties;

    private SiteMonitorResultService service;

    @BeforeEach
    void setUp() {
        service = new SiteMonitorResultService(runRepository, monitorRepository, findingRepository,
                userRepository, mail, mailProperties);
    }

    @Test
    void completionStoresDiffAndQueuesAlertForMeaningfulChanges() {
        UUID monitorId = UUID.randomUUID();
        UUID previousScanId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        MonitorRun run = new MonitorRun();
        run.setId(UUID.randomUUID());
        run.setMonitorId(monitorId);
        run.setScanId(scanId);
        run.setStatus(MonitorRunStatus.RUNNING);
        run.setPreviousScore(80);
        SiteMonitor monitor = new SiteMonitor();
        monitor.setId(monitorId);
        monitor.setUserId(7L);
        monitor.setSiteDomain("example.com");
        monitor.setLocale("ru");
        monitor.setNotificationsEnabled(true);
        monitor.setLastScanId(previousScanId);
        AppUser user = new AppUser();
        user.setEmail("owner@example.com");

        when(runRepository.findByScanId(scanId)).thenReturn(Optional.of(run));
        when(monitorRepository.findById(monitorId)).thenReturn(Optional.of(monitor));
        when(findingRepository.findByScanIdOrderByCreatedAtAsc(previousScanId))
                .thenReturn(List.of(finding("OLD", "/privacy"), finding("SAME", "/")));
        when(findingRepository.findByScanIdOrderByCreatedAtAsc(scanId))
                .thenReturn(List.of(finding("NEW", "/form"), finding("SAME", "/")));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(mailProperties.frontendBaseUrl()).thenReturn("https://okdocs.io");
        Instant completedAt = Instant.parse("2026-07-16T12:00:00Z");

        service.completed(scanId, ScanStatus.COMPLETED, 70, completedAt);

        assertThat(run.getStatus()).isEqualTo(MonitorRunStatus.COMPLETED);
        assertThat(run.getNewFindings()).isEqualTo(1);
        assertThat(run.getResolvedFindings()).isEqualTo(1);
        assertThat(monitor.getLastScanId()).isEqualTo(scanId);
        assertThat(monitor.getLastScore()).isEqualTo(70);
        verify(mail).enqueueMonitoringAlert(eq(scanId), eq("owner@example.com"),
                eq("example.com"), eq(80), eq(70), eq(1), eq(1),
                eq("https://okdocs.io/ru/dashboard/reports/" + scanId), eq("ru"));
    }

    @Test
    void duplicateCompletionIsIdempotent() {
        UUID scanId = UUID.randomUUID();
        MonitorRun run = new MonitorRun();
        run.setStatus(MonitorRunStatus.COMPLETED);
        when(runRepository.findByScanId(scanId)).thenReturn(Optional.of(run));

        service.completed(scanId, ScanStatus.COMPLETED, 90, Instant.now());

        verify(monitorRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(mail, never()).enqueueMonitoringAlert(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static ComplianceFinding finding(String code, String page) {
        ComplianceFinding finding = new ComplianceFinding();
        finding.setCode(code);
        finding.setPageUrl(page);
        finding.setSeverity(FindingSeverity.HIGH);
        return finding;
    }
}
