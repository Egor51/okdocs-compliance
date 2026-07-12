package io.okdocs.compliance.mail.notification;

import io.okdocs.compliance.mail.model.MailType;
import io.okdocs.compliance.mail.queue.MailOutboxService;
import io.okdocs.compliance.mail.template.HandlebarsMailTemplateRenderer;
import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
public class DefaultMailNotificationService implements MailNotificationService {

    private final MailOutboxService outbox;

    @Override
    public void enqueueWelcome(Long userId, String email, String name, String locale) {
        if (!hasEmail(email)) return;
        String lang = HandlebarsMailTemplateRenderer.normalizeLocale(locale);
        outbox.enqueue(MailType.WELCOME, "WELCOME:" + userId, userId.toString(), email,
                "en".equals(lang) ? "Welcome to OKDOCS" : "Добро пожаловать в OKDOCS",
                "welcome", lang, Map.of("name", blankTo(email, name), "email", email));
    }

    @Override
    public void enqueuePasswordReset(UUID resetRequestId, Long userId, String email,
                                     String resetUrl, Instant expiresAt, String locale) {
        if (!hasEmail(email)) return;
        String lang = HandlebarsMailTemplateRenderer.normalizeLocale(locale);
        outbox.enqueue(MailType.PASSWORD_RESET, "PASSWORD_RESET:" + resetRequestId,
                userId.toString(), email,
                "en".equals(lang) ? "Reset your OKDOCS password" : "Восстановление пароля OKDOCS",
                "password_reset", lang,
                Map.of("resetUrl", resetUrl, "expiresAt", expiresAt.toString()));
    }

    @Override
    public void enqueueReportReady(UUID scanId, String email, String siteDomain,
                                   Integer score, String reportUrl, String locale) {
        if (!hasEmail(email)) return;
        String lang = HandlebarsMailTemplateRenderer.normalizeLocale(locale);
        Map<String, Object> model = new HashMap<>();
        model.put("siteDomain", siteDomain == null ? "" : siteDomain);
        model.put("score", score == null ? "" : score);
        model.put("reportUrl", reportUrl);
        outbox.enqueue(MailType.REPORT_READY,
                "REPORT_READY:" + scanId + ":" + sha256(email.trim().toLowerCase()),
                scanId.toString(), email,
                "en".equals(lang) ? "Your OKDOCS report is ready" : "Ваш отчёт OKDOCS готов",
                "report_ready", lang, model);
    }

    @Override
    public void enqueuePromo(UUID campaignId, UUID subscriptionId, String email,
                             String subject, Map<String, Object> model, String locale) {
        if (!hasEmail(email)) return;
        model = new HashMap<>(model);
        model.put("subscriptionId", subscriptionId.toString());
        outbox.enqueue(MailType.PROMO, "PROMO:" + campaignId + ":" + subscriptionId,
                campaignId.toString(), email, subject, "promo", locale, model);
    }

    private static boolean hasEmail(String email) {
        return email != null && !email.isBlank();
    }

    private static String blankTo(String fallback, String value) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
