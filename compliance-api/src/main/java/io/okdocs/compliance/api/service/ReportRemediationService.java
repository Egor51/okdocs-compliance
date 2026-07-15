package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.exception.AccessDeniedToScanException;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.remediation.RemediationRequestResponse;
import io.okdocs.compliance.contracts.remediation.RemediationRequestStatus;
import io.okdocs.compliance.contracts.scan.ScanReportResponse;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.remediation.ReportRemediationRequest;
import io.okdocs.compliance.persistence.remediation.ReportRemediationRequestRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportRemediationService {

    private final ScanCommandService scanCommandService;
    private final ComplianceScanRepository scanRepository;
    private final AppUserRepository appUserRepository;
    private final ReportRemediationRequestRepository requestRepository;

    @Transactional
    public RemediationRequestResponse create(UUID scanId, CompliancePrincipal principal) {
        if (!principal.isUser()) {
            throw new AccessDeniedToScanException(scanId);
        }
        ScanReportResponse report = scanCommandService.getReport(scanId, principal);
        if (report.tier() != ScanTier.PREMIUM) {
            throw new ComplianceValidationException(
                    "Заявка на доработку доступна только для полного отчёта");
        }

        AppUser user = appUserRepository.findById(principal.userId())
                .orElseThrow(() -> new ComplianceValidationException("Пользователь не найден"));
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new ComplianceValidationException("В аккаунте не указан email");
        }
        ComplianceScan scan = scanRepository.findById(scanId).orElseThrow();
        Instant now = Instant.now();
        requestRepository.insertIfAbsent(
                UUID.randomUUID(), scanId, principal.userId(), report.siteUrl(), user.getEmail().trim(),
                RemediationRequestStatus.NEW.name(), normalizeLocale(scan.getLocale()), now);

        ReportRemediationRequest saved = requestRepository.findByScanIdAndUserId(
                        scanId, principal.userId())
                .orElseThrow(() -> new IllegalStateException("Не удалось сохранить заявку на доработку"));
        return toResponse(saved);
    }

    private static RemediationRequestResponse toResponse(ReportRemediationRequest request) {
        return new RemediationRequestResponse(
                request.getId(), request.getScanId(), request.getSiteUrlSnapshot(),
                request.getCustomerEmailSnapshot(), request.getStatus(), request.getCreatedAt());
    }

    private static String normalizeLocale(String locale) {
        return locale == null || locale.isBlank() ? "ru" : locale.trim().toLowerCase();
    }
}
