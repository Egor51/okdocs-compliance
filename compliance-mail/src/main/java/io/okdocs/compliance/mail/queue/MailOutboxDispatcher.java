package io.okdocs.compliance.mail.queue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.mail.config.ComplianceMailProperties;
import io.okdocs.compliance.mail.config.MailMetrics;
import io.okdocs.compliance.mail.model.MailDeliveryResult;
import io.okdocs.compliance.mail.model.MailType;
import io.okdocs.compliance.mail.model.OutboundMail;
import io.okdocs.compliance.mail.security.MailPayloadCipher;
import io.okdocs.compliance.mail.template.MailTemplateRenderer;
import io.okdocs.compliance.mail.transport.MailTransport;
import io.okdocs.compliance.persistence.mail.MailOutboxMessage;
import io.okdocs.compliance.persistence.mail.MailOutboxRepository;
import io.okdocs.compliance.persistence.mail.EmailSubscriptionRepository;
import io.okdocs.compliance.persistence.mail.EmailSubscriptionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class MailOutboxDispatcher {

    private static final TypeReference<Map<String, Object>> MODEL_TYPE = new TypeReference<>() {};

    private final MailOutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final MailPayloadCipher payloadCipher;
    private final MailTemplateRenderer renderer;
    private final MailTransport transport;
    private final ComplianceMailProperties properties;
    private final TransactionOperations transactions;
    private final EmailSubscriptionRepository subscriptionRepository;
    private final MailMetrics metrics;
    private final String instanceId = "mail-" + UUID.randomUUID();

    public MailOutboxDispatcher(MailOutboxRepository repository,
                                ObjectMapper objectMapper,
                                MailPayloadCipher payloadCipher,
                                MailTemplateRenderer renderer,
                                MailTransport transport,
                                ComplianceMailProperties properties,
                                EmailSubscriptionRepository subscriptionRepository,
                                MailMetrics metrics,
                                PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.payloadCipher = payloadCipher;
        this.renderer = renderer;
        this.transport = transport;
        this.properties = properties;
        this.subscriptionRepository = subscriptionRepository;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${compliance.mail.poll-interval-ms:5000}")
    public void dispatchPending() {
        UUID lockToken = UUID.randomUUID();
        List<MailOutboxMessage> batch = transactions.execute(s ->
                repository.claimBatch(instanceId, lockToken, properties.batchSize()));
        if (batch == null || batch.isEmpty()) return;
        batch.forEach(message -> deliver(message, lockToken));
    }

    private void deliver(MailOutboxMessage message, UUID lockToken) {
        Instant startedAt = Instant.now();
        MailDeliveryResult result;
        try {
            Map<String, Object> model = objectMapper.readValue(
                    payloadCipher.decrypt(message.getModelPayload()), MODEL_TYPE);
            if (MailType.PROMO.name().equals(message.getMailType()) && !isSubscribed(model)) {
                markDelivered(message, lockToken, "CANCELLED");
                return;
            }
            String html = renderer.render(message.getTemplateName(), message.getLocale(), model);
            String text = toPlainText(html);
            OutboundMail mail = new OutboundMail(
                    message.getId(), MailType.valueOf(message.getMailType()), message.getRecipient(),
                    message.getSubject(), html, text, properties.replyTo());
            result = transport.send(mail);
        } catch (Exception e) {
            result = MailDeliveryResult.permanent(e.getMessage());
        }

        switch (result.outcome()) {
            case DELIVERED -> markDelivered(message, lockToken, "SENT");
            case SIMULATED -> markDelivered(message, lockToken, "SIMULATED");
            case PERMANENT_FAILURE -> markDead(message, lockToken, result.error());
            case RETRYABLE_FAILURE -> retryOrDead(message, lockToken, result.error());
        }
        metrics.delivery(message.getMailType(), result.outcome().name(),
                Duration.between(startedAt, Instant.now()));
    }

    private boolean isSubscribed(Map<String, Object> model) {
        Object raw = model.get("subscriptionId");
        if (raw == null) return false;
        try {
            return subscriptionRepository.findById(UUID.fromString(raw.toString()))
                    .map(s -> s.getStatus() == EmailSubscriptionStatus.SUBSCRIBED)
                    .orElse(false);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void markDelivered(MailOutboxMessage message, UUID lockToken, String status) {
        int updated = transactions.execute(s -> repository.markDeliveredIfLocked(
                message.getId(), lockToken, status, Instant.now()));
        if (updated == 0) log.warn("Mail {} delivered but lock was lost", message.getId());
        else log.info("Mail processed: messageId={} type={} status={}",
                message.getId(), message.getMailType(), status);
    }

    private void retryOrDead(MailOutboxMessage message, UUID lockToken, String error) {
        int attempts = message.getAttemptCount() + 1;
        if (attempts >= properties.maxAttempts()) {
            markDead(message, lockToken, error, attempts);
            return;
        }
        Instant next = Instant.now().plus(backoff(attempts));
        transactions.execute(s -> repository.markRetryIfLocked(
                message.getId(), lockToken, attempts, truncate(error), next));
        log.warn("Mail delivery retry scheduled: messageId={} type={} attempt={} next={}",
                message.getId(), message.getMailType(), attempts, next);
    }

    private void markDead(MailOutboxMessage message, UUID lockToken, String error) {
        markDead(message, lockToken, error, message.getAttemptCount() + 1);
    }

    private void markDead(MailOutboxMessage message, UUID lockToken, String error, int attempts) {
        transactions.execute(s -> repository.markDeadIfLocked(
                message.getId(), lockToken, attempts, truncate(error)));
        log.error("Mail delivery dead: messageId={} type={} attempts={} error={}",
                message.getId(), message.getMailType(), attempts, truncate(error));
    }

    private Duration backoff(int attempts) {
        long base = properties.backoffBase().toMillis();
        long max = properties.backoffMax().toMillis();
        long shift = Math.min(attempts - 1L, 20L);
        return Duration.ofMillis((long) Math.min((double) max, base * Math.pow(2, shift)));
    }

    private static String truncate(String error) {
        if (error == null) return null;
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }

    private static String toPlainText(String html) {
        return html.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
