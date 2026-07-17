package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.config.MonitoringProperties;
import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.contracts.enums.UserRole;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.monitoring.CreateSiteMonitorRequest;
import io.okdocs.compliance.contracts.monitoring.SiteMonitorDto;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.monitoring.MonitorRunRepository;
import io.okdocs.compliance.persistence.monitoring.SiteMonitor;
import io.okdocs.compliance.persistence.monitoring.SiteMonitorRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteMonitorServiceTest {

    @Mock private SiteMonitorRepository monitorRepository;
    @Mock private MonitorRunRepository runRepository;
    @Mock private ComplianceScanRepository scanRepository;
    @Mock private AppUserRepository userRepository;
    @Mock private UrlValidatorService urlValidator;
    @Mock private ComplianceApiProperties properties;
    @Mock private SiteMonitorExecutionService executionService;

    private SiteMonitorService service;
    private final CompliancePrincipal principal = CompliancePrincipal.user(7L, UserRole.USER);

    @BeforeEach
    void setUp() {
        service = new SiteMonitorService(monitorRepository, runRepository, scanRepository,
                userRepository, urlValidator, properties, new MonitoringProperties(null),
                executionService);
    }

    @Test
    void completedPremiumReportBecomesBaselineWithoutImmediateDebit() {
        UUID baselineId = UUID.randomUUID();
        AppUser user = paidUser(UserPlan.PRO);
        ComplianceScan baseline = new ComplianceScan();
        baseline.setId(baselineId);
        baseline.setUserId(7L);
        baseline.setSiteDomain("example.com");
        baseline.setSiteUrl("https://example.com");
        baseline.setJurisdiction(ScanJurisdiction.RU);
        baseline.setTier(ScanTier.PREMIUM);
        baseline.setKind(ScanKind.CABINET_PREMIUM);
        baseline.setStatus(ScanStatus.COMPLETED);
        baseline.setScore(82);
        baseline.setFinishedAt(Instant.parse("2026-07-16T10:00:00Z"));

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(monitorRepository.countByUserId(7L)).thenReturn(0L);
        when(urlValidator.validate("https://example.com"))
                .thenReturn(new UrlValidatorService.ValidatedUrl(
                        "https://example.com", "example.com"));
        when(properties.scan()).thenReturn(new ComplianceApiProperties.Scan(
                1, 5, 100, 7, 7, Set.of(ScanJurisdiction.RU)));
        when(scanRepository.findById(baselineId)).thenReturn(Optional.of(baseline));
        when(monitorRepository.existsByUserIdAndSiteDomainAndJurisdiction(
                7L, "example.com", ScanJurisdiction.RU)).thenReturn(false);
        when(monitorRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            SiteMonitor monitor = invocation.getArgument(0);
            monitor.setId(UUID.randomUUID());
            return monitor;
        });

        SiteMonitorDto result = service.create(new CreateSiteMonitorRequest(
                "https://example.com", "RU", 3, "Europe/Moscow", "ru",
                baselineId, true), principal);

        assertThat(result.lastScanId()).isEqualTo(baselineId);
        assertThat(result.lastScore()).isEqualTo(82);
        assertThat(result.intervalDays()).isEqualTo(3);
        assertThat(result.nextRunAt()).isAfter(Instant.now().plusSeconds(2 * 24 * 60 * 60));
        verify(executionService, never()).executeManual(any(), any());
    }

    @Test
    void freePlanCannotCreateMonitor() {
        AppUser user = paidUser(UserPlan.FREE);
        user.setPlanRenewsAt(null);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.create(new CreateSiteMonitorRequest(
                "https://example.com", "RU", 3, "UTC", "ru", null, true), principal))
                .isInstanceOf(ComplianceValidationException.class)
                .hasMessageContaining("PRO");

        verify(urlValidator, never()).validate(any());
        verify(monitorRepository, never()).saveAndFlush(any());
    }

    private static AppUser paidUser(UserPlan plan) {
        AppUser user = new AppUser();
        user.setId(7L);
        user.setPlan(plan);
        user.setPlanRenewsAt(Instant.now().plusSeconds(30L * 24 * 60 * 60));
        return user;
    }
}
