package io.okdocs.compliance.mail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "compliance.mail")
public record ComplianceMailProperties(
        Boolean enabled,
        String from,
        String replyTo,
        String frontendBaseUrl,
        String payloadEncryptionKey,
        String unsubscribeSecret,
        Integer batchSize,
        Integer maxAttempts,
        Duration backoffBase,
        Duration backoffMax,
        Duration payloadRetention
) {
    public ComplianceMailProperties {
        if (enabled == null) enabled = false;
        if (from == null || from.isBlank()) from = "no-reply@okdocs.io";
        if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) frontendBaseUrl = "http://localhost:3000";
        frontendBaseUrl = frontendBaseUrl.replaceAll("/+$", "");
        if (batchSize == null || batchSize < 1) batchSize = 50;
        if (maxAttempts == null || maxAttempts < 1) maxAttempts = 6;
        if (backoffBase == null) backoffBase = Duration.ofSeconds(30);
        if (backoffMax == null) backoffMax = Duration.ofHours(1);
        if (payloadRetention == null) payloadRetention = Duration.ofHours(48);
        if (enabled && (payloadEncryptionKey == null || payloadEncryptionKey.isBlank())) {
            throw new IllegalArgumentException(
                    "compliance.mail.payload-encryption-key is required when mail is enabled");
        }
        if (enabled && payloadEncryptionKey.length() < 32) {
            throw new IllegalArgumentException(
                    "compliance.mail.payload-encryption-key must contain at least 32 characters");
        }
        if (unsubscribeSecret == null || unsubscribeSecret.isBlank()) {
            unsubscribeSecret = payloadEncryptionKey;
        }
    }
}
