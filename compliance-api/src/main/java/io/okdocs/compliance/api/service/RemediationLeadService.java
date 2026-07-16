package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.remediation.RemediationLeadRequest;
import io.okdocs.compliance.contracts.remediation.RemediationLeadResponse;
import io.okdocs.compliance.contracts.remediation.RemediationRequestStatus;
import io.okdocs.compliance.persistence.remediation.RemediationLead;
import io.okdocs.compliance.persistence.remediation.RemediationLeadRepository;
import io.okdocs.compliance.mail.notification.MailNotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
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
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (value.isEmpty()) {
            throw new ComplianceValidationException("URL не указан");
        }
        if (!value.matches("(?i)^https?://.*")) value = "https://" + value;
        try {
            URI parsed = new URI(value);
            String scheme = parsed.getScheme() == null
                    ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new ComplianceValidationException("Поддерживаются только http и https");
            }
            if (parsed.getHost() == null || parsed.getHost().isBlank() || parsed.getUserInfo() != null) {
                throw new ComplianceValidationException("Некорректный адрес сайта");
            }
            String domain = IDN.toASCII(parsed.getHost()).toLowerCase(Locale.ROOT);
            if (domain.length() > 255) {
                throw new ComplianceValidationException("Домен сайта слишком длинный");
            }
            URI normalized = new URI(
                    scheme, null, domain, parsed.getPort(),
                    parsed.getPath(), parsed.getQuery(), null).normalize();
            return new NormalizedSite(normalized.toASCIIString(), domain);
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new ComplianceValidationException("Некорректный адрес сайта");
        }
    }

    private record NormalizedSite(String normalizedUrl, String domain) {
    }
}
