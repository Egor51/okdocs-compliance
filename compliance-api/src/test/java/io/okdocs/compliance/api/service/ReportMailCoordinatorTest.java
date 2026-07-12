package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.mail.config.ComplianceMailProperties;
import io.okdocs.compliance.mail.notification.MailNotificationService;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanReportRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportMailCoordinatorTest {
    @Mock ComplianceScanRepository scans;
    @Mock ComplianceScanReportRepository reports;
    @Mock MailNotificationService mail;
    @Mock ComplianceMailProperties properties;
    ReportMailCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new ReportMailCoordinator(scans, reports, mail, properties);
    }

    @Test
    void queuesCompletedReport() {
        when(properties.frontendBaseUrl()).thenReturn("https://app.example");
        UUID id = UUID.randomUUID();
        ComplianceScan scan = scan(id, ScanStatus.COMPLETED);
        when(scans.findById(id)).thenReturn(Optional.of(scan));
        when(reports.existsById(id)).thenReturn(true);

        coordinator.enqueueIfReady(id);

        verify(mail).enqueueReportReady(eq(id), eq("buyer@example.com"), eq("example.com"),
                eq(73), contains("/en/dashboard/scans/" + id), eq("en"));
    }

    @Test
    void ignoresNonTerminalScan() {
        UUID id = UUID.randomUUID();
        when(scans.findById(id)).thenReturn(Optional.of(scan(id, ScanStatus.CRAWLING)));
        coordinator.enqueueIfReady(id);
        verify(mail, never()).enqueueReportReady(anyUuid(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static UUID anyUuid() {
        return org.mockito.ArgumentMatchers.any(UUID.class);
    }

    private static ComplianceScan scan(UUID id, ScanStatus status) {
        ComplianceScan scan = new ComplianceScan();
        scan.setId(id);
        scan.setStatus(status);
        scan.setBuyerEmail("buyer@example.com");
        scan.setSiteDomain("example.com");
        scan.setScore(73);
        scan.setLocale("en");
        return scan;
    }
}
