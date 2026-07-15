package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.enums.UserRole;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.remediation.RemediationRequestStatus;
import io.okdocs.compliance.contracts.scan.ScanReportResponse;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.remediation.ReportRemediationRequest;
import io.okdocs.compliance.persistence.remediation.ReportRemediationRequestRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportRemediationServiceTest {

    @Mock private ScanCommandService scanCommandService;
    @Mock private ComplianceScanRepository scanRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private ReportRemediationRequestRepository requestRepository;

    private ReportRemediationService service;
    private final CompliancePrincipal principal = CompliancePrincipal.user(11L, UserRole.USER);

    @BeforeEach
    void setUp() {
        service = new ReportRemediationService(
                scanCommandService, scanRepository, appUserRepository, requestRepository);
    }

    @Test
    void createsIdempotentRequestFromTrustedAccountAndReportData() {
        UUID scanId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-15T08:00:00Z");
        AppUser user = new AppUser();
        user.setId(11L);
        user.setEmail(" customer@example.com ");
        ComplianceScan scan = new ComplianceScan();
        scan.setId(scanId);
        scan.setLocale("RU");
        ReportRemediationRequest stored = new ReportRemediationRequest();
        stored.setId(UUID.randomUUID());
        stored.setScanId(scanId);
        stored.setUserId(11L);
        stored.setSiteUrlSnapshot("https://trusted.example");
        stored.setCustomerEmailSnapshot("customer@example.com");
        stored.setStatus(RemediationRequestStatus.NEW);
        stored.setLocale("ru");
        stored.setCreatedAt(createdAt);
        stored.setUpdatedAt(createdAt);

        when(scanCommandService.getReport(scanId, principal))
                .thenReturn(report(scanId, ScanTier.PREMIUM));
        when(appUserRepository.findById(11L)).thenReturn(Optional.of(user));
        when(scanRepository.findById(scanId)).thenReturn(Optional.of(scan));
        when(requestRepository.findByScanIdAndUserId(scanId, 11L))
                .thenReturn(Optional.of(stored));

        var response = service.create(scanId, principal);

        assertThat(response.id()).isEqualTo(stored.getId());
        assertThat(response.siteUrl()).isEqualTo("https://trusted.example");
        assertThat(response.email()).isEqualTo("customer@example.com");
        assertThat(response.status()).isEqualTo(RemediationRequestStatus.NEW);
        verify(requestRepository).insertIfAbsent(
                any(UUID.class), eq(scanId), eq(11L), eq("https://trusted.example"),
                eq("customer@example.com"), eq("NEW"), eq("ru"), any(Instant.class));
    }

    @Test
    void rejectsRequestForFreeReportBeforeReadingAccountData() {
        UUID scanId = UUID.randomUUID();
        when(scanCommandService.getReport(scanId, principal))
                .thenReturn(report(scanId, ScanTier.FREE));

        assertThatThrownBy(() -> service.create(scanId, principal))
                .isInstanceOf(ComplianceValidationException.class)
                .hasMessageContaining("полного отчёта");

        verifyNoInteractions(appUserRepository, scanRepository, requestRepository);
    }

    @Test
    void rejectsGuestBeforeReportLookup() {
        var guest = CompliancePrincipal.guest(UUID.randomUUID());

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), guest))
                .isInstanceOf(io.okdocs.compliance.contracts.exception.AccessDeniedToScanException.class);

        verify(scanCommandService, never()).getReport(any(), any());
    }

    private static ScanReportResponse report(UUID id, ScanTier tier) {
        Instant createdAt = Instant.parse("2026-07-15T08:00:00Z");
        return new ScanReportResponse(
                id, "https://trusted.example", "trusted.example", ScanJurisdiction.RU,
                ScanStatus.COMPLETED, 73, tier, null, null, List.of(), null, null, null,
                1_500L, createdAt, createdAt.plusSeconds(2));
    }
}
