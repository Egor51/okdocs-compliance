package io.okdocs.compliance.mail.subscription;

import io.okdocs.compliance.mail.config.ComplianceMailProperties;
import io.okdocs.compliance.persistence.mail.EmailSubscription;
import io.okdocs.compliance.persistence.mail.EmailSubscriptionRepository;
import io.okdocs.compliance.persistence.mail.EmailSubscriptionStatus;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class EmailSubscriptionService {

    private static final String DISABLED_LOCAL_SECRET = "okdocs-mail-disabled-local-unsubscribe-secret";

    private final EmailSubscriptionRepository repository;
    private final ComplianceMailProperties properties;
    private final byte[] signingKey;

    public EmailSubscriptionService(EmailSubscriptionRepository repository,
                                    ComplianceMailProperties properties) {
        this.repository = repository;
        this.properties = properties;
        String secret = properties.unsubscribeSecret();
        if (secret == null || secret.isBlank()) secret = DISABLED_LOCAL_SECRET;
        this.signingKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public EmailSubscription subscribe(Long userId, String email, String locale,
                                       String source, String consentIp) {
        String normalized = normalizeEmail(email);
        EmailSubscription subscription = repository.findByNormalizedEmail(normalized)
                .orElseGet(EmailSubscription::new);
        subscription.setUserId(userId != null ? userId : subscription.getUserId());
        subscription.setEmail(email.trim());
        subscription.setNormalizedEmail(normalized);
        subscription.setLocale(normalizeLocale(locale));
        subscription.setSource(source);
        subscription.setConsentIp(consentIp);
        subscription.setConsentAt(Instant.now());
        subscription.setStatus(EmailSubscriptionStatus.SUBSCRIBED);
        subscription.setUnsubscribedAt(null);
        return repository.save(subscription);
    }

    @Transactional
    public boolean unsubscribe(String token) {
        UUID id = verifyToken(token);
        Optional<EmailSubscription> found = repository.findById(id);
        if (found.isEmpty()) return false;
        EmailSubscription subscription = found.get();
        if (subscription.getStatus() != EmailSubscriptionStatus.UNSUBSCRIBED) {
            subscription.setStatus(EmailSubscriptionStatus.UNSUBSCRIBED);
            subscription.setUnsubscribedAt(Instant.now());
            repository.save(subscription);
        }
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<EmailSubscription> activeByEmail(String email) {
        return repository.findByNormalizedEmail(normalizeEmail(email))
                .filter(s -> s.getStatus() == EmailSubscriptionStatus.SUBSCRIBED);
    }

    public String unsubscribeUrl(EmailSubscription subscription) {
        return properties.frontendBaseUrl() + "/" + normalizeLocale(subscription.getLocale())
                + "/unsubscribe?token=" + tokenFor(subscription.getId());
    }

    public String tokenFor(UUID id) {
        String value = id.toString();
        return value + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(value));
    }

    private UUID verifyToken(String token) {
        if (token == null) throw new IllegalArgumentException("Invalid unsubscribe token");
        int separator = token.indexOf('.');
        if (separator <= 0) throw new IllegalArgumentException("Invalid unsubscribe token");
        String value = token.substring(0, separator);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(token.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid unsubscribe token");
        }
        if (!MessageDigest.isEqual(hmac(value), actual)) {
            throw new IllegalArgumentException("Invalid unsubscribe token");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid unsubscribe token");
        }
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Email is required");
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeLocale(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("en") ? "en" : "ru";
    }
}
