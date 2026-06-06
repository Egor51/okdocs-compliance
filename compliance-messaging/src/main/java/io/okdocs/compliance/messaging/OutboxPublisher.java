package io.okdocs.compliance.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.contracts.enums.OutboxStatus;
import io.okdocs.compliance.persistence.outbox.OutboxEvent;
import io.okdocs.compliance.persistence.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox relay (§4.5) — общий для api и worker.
 * <p>
 * Каждые 5 секунд атомарно захватывает батч готовых к публикации событий
 * ({@code lockBatch} с {@code FOR UPDATE SKIP LOCKED}), публикует каждое в Kafka-топик из поля
 * {@code topic}, и помечает результат:
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
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;

    /** Идентификатор инстанса для lock owner'а (по умолчанию случайный на старте процесса). */
    private final String instanceId = "outbox-" + UUID.randomUUID();

    // TODO(scale): захват строк и Kafka send(...).get() идут в ОДНОЙ транзакции — БД-lock на строках
    // держится на время сетевого ожидания Kafka. Функционально корректно (SKIP LOCKED исключает
    // дубли publisher'ов), но при росте нагрузки лучше: claim в короткой транзакции (lease+lockedBy),
    // publish ВНЕ транзакции, затем отдельный условный update статуса с fencing по lockedBy.
    // Отложено осознанно — вернуться при реальной нагрузке.
    @Scheduled(fixedDelayString = "${compliance.outbox.poll-interval-ms:5000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.lockBatch(instanceId, properties.batchSize());
        if (batch.isEmpty()) {
            return;
        }
        log.debug("Outbox relay: захвачено {} событий инстансом {}", batch.size(), instanceId);
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            Object payload = objectMapper.readValue(event.getPayload(), JsonNode.class);
            kafkaTemplate.send(event.getTopic(), event.getEventKey(), payload).get();
            markPublished(event);
        } catch (Exception e) {
            markRetryOrDead(event, e);
        }
    }

    private void markPublished(OutboxEvent event) {
        event.setStatus(OutboxStatus.PUBLISHED);
        event.setPublishedAt(Instant.now());
        event.setLockedAt(null);
        event.setLockedBy(null);
        outboxRepository.save(event);
    }

    private void markRetryOrDead(OutboxEvent event, Exception e) {
        event.setRetryCount(event.getRetryCount() + 1);
        event.setLastError(truncate(e.getMessage()));
        event.setLockedAt(null);
        event.setLockedBy(null);
        if (event.getRetryCount() >= properties.maxRetries()) {
            event.setStatus(OutboxStatus.DEAD);
            log.error("Outbox событие {} ({}) переведено в DEAD после {} попыток: {}",
                    event.getId(), event.getEventType(), event.getRetryCount(), event.getLastError());
        } else {
            event.setNextAttemptAt(Instant.now().plus(backoff(event.getRetryCount())));
            log.warn("Outbox событие {} ({}) не опубликовано (попытка {}), повтор в {}: {}",
                    event.getId(), event.getEventType(), event.getRetryCount(),
                    event.getNextAttemptAt(), event.getLastError());
        }
        outboxRepository.save(event);
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
