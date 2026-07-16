package io.okdocs.compliance.mail.notification;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface MailNotificationService {
    void enqueueWelcome(Long userId, String email, String name, String locale);
    void enqueuePasswordReset(UUID resetRequestId, Long userId, String email,
                              String resetUrl, Instant expiresAt, String locale);
    void enqueueReportReady(UUID scanId, String email, String siteDomain,
                            Integer score, String reportUrl, String locale);
    void enqueueRemediationRequest(UUID requestId, String recipient, String siteUrl,
                                   String name, String email, String phone,
                                   Instant submittedAt, String locale);
    void enqueuePromo(UUID campaignId, UUID subscriptionId, String email,
                      String subject, Map<String, Object> model, String locale);
}
