package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.mail.config.ComplianceMailProperties;
import io.okdocs.compliance.mail.notification.MailNotificationService;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanReportRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportMailCoordinator {

    private final ComplianceScanRepository scanRepository;
    private final ComplianceScanReportRepository reportRepository;
    private final MailNotificationService mailNotificationService;
    private final ComplianceMailProperties mailProperties;

    @Transactional
    public void enqueueIfReady(UUID scanId) {
        ComplianceScan scan = scanRepository.findById(scanId).orElse(null);
        if (scan == null || (scan.getStatus() != ScanStatus.COMPLETED
                && scan.getStatus() != ScanStatus.PARTIAL)) return;
        if (scan.getBuyerEmail() == null || scan.getBuyerEmail().isBlank()) return;
        if (!reportRepository.existsById(scanId)) return;

        String locale = normalizeLocale(scan.getLocale());
        String reportUrl = mailProperties.frontendBaseUrl() + "/" + locale
                + "/dashboard/scans/" + scanId;
        mailNotificationService.enqueueReportReady(scanId, scan.getBuyerEmail(), scan.getSiteDomain(),
                scan.getScore(), reportUrl, locale);
    }

    private static String normalizeLocale(String locale) {
        return "en".equalsIgnoreCase(locale) ? "en" : "ru";
    }
}
