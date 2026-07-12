package io.okdocs.compliance.mail.queue;

import io.okdocs.compliance.mail.config.ComplianceMailProperties;
import io.okdocs.compliance.persistence.auth.PasswordResetTokenRepository;
import io.okdocs.compliance.persistence.mail.MailOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@RequiredArgsConstructor
public class MailRetentionJob {
    private final MailOutboxRepository mailRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final ComplianceMailProperties properties;

    @Scheduled(cron = "${compliance.mail.retention-cron:0 30 3 * * *}")
    @Transactional
    public void cleanup() {
        Instant now = Instant.now();
        mailRepository.purgeDeliveredPayloads(now.minus(properties.payloadRetention()), now);
        resetTokenRepository.deleteByExpiresAtBefore(now.minus(properties.payloadRetention()));
    }
}
