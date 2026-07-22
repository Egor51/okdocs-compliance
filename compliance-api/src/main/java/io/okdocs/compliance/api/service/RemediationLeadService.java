package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.remediation.RemediationLeadRequest;
import io.okdocs.compliance.contracts.remediation.RemediationLeadResponse;
import io.okdocs.compliance.contracts.remediation.RemediationRequestStatus;
import io.okdocs.compliance.contracts.security.HttpUrlNormalizer;
import io.okdocs.compliance.persistence.remediation.RemediationLead;
import io.okdocs.compliance.persistence.remediation.RemediationLeadRepository;
import io.okdocs.compliance.mail.notification.MailNotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class RemediationLeadService {

    private static final List<RemediationRequestStatus> ACTIVE_STATUSES = List.of(
            RemediationRequestStatus.NEW,
            RemediationRequestStatus.CONTACTED,
            RemediationRequestStatus.IN_PROGRESS);

    private final RemediationLeadRepository repository;
    private final RateLimitService rateLimitService;
    private final MailNotificationService mailNotificationService;
    private final String notificationRecipient;

    public RemediationLeadService(
            RemediationLeadRepository repository,
            RateLimitService rateLimitService,
            MailNotificationService mailNotificationService,
            @Value("${compliance.remediation.notification-recipient:}")
            String notificationRecipient) {
        this.repository = repository;
        this.rateLimitService = rateLimitService;
        this.mailNotificationService = mailNotificationService;
        this.notificationRecipient = notificationRecipient;
    }

    @Transactional
    public RemediationLeadResponse create(RemediationLeadRequest request, String ipAddress) {
        if (!request.consent()) {
            throw new ComplianceValidationException(
                    "Требуется согласие на обработку персональных данных");
        }
        rateLimitService.checkRemediationRequestAllowed(ipAddress);
        NormalizedSite site = normalizeSite(request.siteUrl());
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String locale = request.locale().trim().toLowerCase(Locale.ROOT);
        Instant now = Instant.now();

        repository.insertIfAbsent(
                UUID.randomUUID(), site.normalizedUrl(), site.domain(), request.name().trim(), email,
                blankToNull(request.phone()), locale, RemediationRequestStatus.NEW.name(), now,
                normalizeIp(ipAddress), now);

        RemediationLead lead = repository
                .findFirstByContactEmailIgnoreCaseAndSiteDomainAndStatusInOrderByCreatedAtDesc(
                        email, site.domain(), ACTIVE_STATUSES)
                .orElseThrow(() -> new IllegalStateException("Не удалось сохранить заявку на доработку"));
        mailNotificationService.enqueueRemediationRequest(
                lead.getId(), notificationRecipient, site.normalizedUrl(), request.name().trim(),
                email, blankToNull(request.phone()), lead.getCreatedAt(), locale);
        return new RemediationLeadResponse(lead.getId(), lead.getStatus(), lead.getCreatedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeIp(String ipAddress) {
        String value = ipAddress == null || ipAddress.isBlank() ? "unknown" : ipAddress.trim();
        return value.length() <= 45 ? value : value.substring(0, 45);
    }

    /** Здесь URL только сохраняется, сетевого запроса нет — DNS lookup формы не требуется. */
    private static NormalizedSite normalizeSite(String rawUrl) {
        try {
            var normalized = HttpUrlNormalizer.normalize(rawUrl, true);
            if (normalized.host().length() > 255) {
                throw new ComplianceValidationException("Домен сайта слишком длинный");
            }
            return new NormalizedSite(normalized.url(), normalized.host());
        } catch (IllegalArgumentException e) {
            throw new ComplianceValidationException("Некорректный адрес сайта");
        }
    }

    private record NormalizedSite(String normalizedUrl, String domain) {
    }
}
