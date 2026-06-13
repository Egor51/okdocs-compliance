package io.okdocs.compliance.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.persistence.outbox.OutboxEvent;
import io.okdocs.compliance.persistence.outbox.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox relay (§4.5) — общий для api и worker.
 * <p>
 * Каждые 5 секунд атомарно захватывает батч готовых к публикации событий короткой транзакцией
 * ({@code claimBatch} с {@code FOR UPDATE SKIP LOCKED}), публикует каждое в Kafka-топик из поля
 * {@code topic} вне DB-транзакции, и помечает результат условным update по {@code lockToken}:
 * <ul>
 *   <li>успех → {@code PUBLISHED} + {@code publishedAt};</li>
 *   <li>ошибка → остаётся {@code PENDING}, {@code retryCount++}, {@code nextAttemptAt} с
 *       экспоненциальным backoff, снимается lock;</li>
 *   <li>{@code retryCount >= maxRetries} → {@code DEAD}.</li>
 * </ul>
 * {@code SKIP LOCKED} убирает дубли от конкуренции publisher'ов, но не отменяет at-least-once
 * Kafka — консьюмеры обязаны быть идемпотентны.
 */
@Slf4j
@Component
public class OutboxPublisher {

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;
    private final TransactionOperations transactions;

    /** Идентификатор инстанса для lock owner'а (по умолчанию случайный на старте процесса). */
    private final String instanceId = "outbox-" + UUID.randomUUID();

    @Autowired
    public OutboxPublisher(OutboxEventRepository outboxRepository,
                           KafkaTemplate<String, Object> kafkaTemplate,
                           ObjectMapper objectMapper,
                           OutboxProperties properties,
                           PlatformTransactionManager transactionManager) {
        this(outboxRepository, kafkaTemplate, objectMapper, properties, new TransactionTemplate(transactionManager));
    }

    OutboxPublisher(OutboxEventRepository outboxRepository,
                    KafkaTemplate<String, Object> kafkaTemplate,
                    ObjectMapper objectMapper,
                    OutboxProperties properties,
                    TransactionOperations transactions) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${compliance.outbox.poll-interval-ms:5000}")
    public void publishPending() {
        UUID lockToken = UUID.randomUUID();
        List<OutboxEvent> batch = transactions.execute(s ->
                outboxRepository.claimBatch(instanceId, lockToken, properties.batchSize()));
        if (batch.isEmpty()) {
            return;
        }
        log.debug("Outbox relay: захвачено {} событий инстансом {}", batch.size(), instanceId);
        for (OutboxEvent event : batch) {
            publishOne(event, lockToken);
        }
    }

    private void publishOne(OutboxEvent event, UUID lockToken) {
        try {
            Object payload = objectMapper.readValue(event.getPayload(), JsonNode.class);
            kafkaTemplate.send(event.getTopic(), event.getEventKey(), payload).get();
            markPublished(event, lockToken);
        } catch (Exception e) {
            markRetryOrDead(event, lockToken, e);
        }
    }

    private void markPublished(OutboxEvent event, UUID lockToken) {
        int updated = transactions.execute(s ->
                outboxRepository.markPublishedIfLocked(event.getId(), lockToken, Instant.now()));
        if (updated == 0) {
            log.warn("Outbox событие {} ({}) опубликовано, но lock уже потерян — статус не изменён",
                    event.getId(), event.getEventType());
        }
    }

    private void markRetryOrDead(OutboxEvent event, UUID lockToken, Exception e) {
        int retryCount = event.getRetryCount() + 1;
        String lastError = truncate(e.getMessage());
        int updated;
        if (retryCount >= properties.maxRetries()) {
            updated = transactions.execute(s ->
                    outboxRepository.markDeadIfLocked(event.getId(), lockToken, retryCount, lastError));
            if (updated > 0) {
                log.error("Outbox событие {} ({}) переведено в DEAD после {} попыток: {}",
                        event.getId(), event.getEventType(), retryCount, lastError);
            }
        } else {
            Instant nextAttemptAt = Instant.now().plus(backoff(retryCount));
            updated = transactions.execute(s ->
                    outboxRepository.markRetryIfLocked(event.getId(), lockToken, retryCount, lastError, nextAttemptAt));
            if (updated > 0) {
                log.warn("Outbox событие {} ({}) не опубликовано (попытка {}), повтор в {}: {}",
                        event.getId(), event.getEventType(), retryCount, nextAttemptAt, lastError);
            }
        }
        if (updated == 0) {
            log.warn("Outbox событие {} ({}) не обновлено после publish failure: lock уже потерян",
                    event.getId(), event.getEventType());
        }
    }

    /** Экспоненциальный backoff: base * 2^(retry-1), ограничен потолком. */
    private Duration backoff(int retryCount) {
        long base = properties.backoffBase().toMillis();
        long max = properties.backoffMax().toMillis();
        long shift = Math.min(retryCount - 1, 20); // защита от переполнения
        long delay = (long) Math.min((double) max, base * Math.pow(2, shift));
        return Duration.ofMillis(delay);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}
