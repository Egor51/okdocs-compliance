package io.okdocs.compliance.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.contracts.enums.OutboxStatus;
import io.okdocs.compliance.persistence.outbox.OutboxEvent;
import io.okdocs.compliance.persistence.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Failure-semantics outbox relay ({@link OutboxPublisher}): успех → PUBLISHED; ошибка публикации →
 * остаётся PENDING с retryCount++/backoff; после исчерпания maxRetries → DEAD.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock OutboxEventRepository repository;
    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    @Captor ArgumentCaptor<UUID> lockTokenCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxProperties properties;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        // maxRetries=3, base=10s, max=10m
        properties = new OutboxProperties(3, 100, Duration.ofSeconds(10), Duration.ofMinutes(10));
        TransactionOperations transactions = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        publisher = new OutboxPublisher(repository, kafkaTemplate, objectMapper, properties, transactions);
    }

    @Test
    void successfulPublish_marksPublished() {
        OutboxEvent event = pending(0);
        when(repository.claimBatch(anyString(), any(UUID.class), eq(100))).thenReturn(List.of(event));
        when(kafkaTemplate.send(eq("test.topic"), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markPublishedIfLocked(eq(event.getId()), any(UUID.class), any(Instant.class))).thenReturn(1);

        publisher.publishPending();

        verify(repository).claimBatch(anyString(), lockTokenCaptor.capture(), eq(100));
        verify(repository).markPublishedIfLocked(eq(event.getId()), eq(lockTokenCaptor.getValue()), any(Instant.class));
    }

    @Test
    void publishFailure_belowMaxRetries_staysPendingWithBackoff() {
        OutboxEvent event = pending(0);
        when(repository.claimBatch(anyString(), any(UUID.class), eq(100))).thenReturn(List.of(event));
        when(kafkaTemplate.send(eq("test.topic"), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        when(repository.markRetryIfLocked(eq(event.getId()), any(UUID.class), eq(1), contains("broker down"), any(Instant.class)))
                .thenReturn(1);

        Instant before = Instant.now();
        publisher.publishPending();

        verify(repository).markRetryIfLocked(eq(event.getId()), any(UUID.class), eq(1),
                contains("broker down"), org.mockito.ArgumentMatchers.argThat(t -> t.isAfter(before)));
    }

    @Test
    void publishFailure_atMaxRetries_marksDead() {
        // retryCount уже 2; ещё одна неудача → 3 == maxRetries → DEAD.
        OutboxEvent event = pending(2);
        when(repository.claimBatch(anyString(), any(UUID.class), eq(100))).thenReturn(List.of(event));
        when(kafkaTemplate.send(eq("test.topic"), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("still down")));
        when(repository.markDeadIfLocked(eq(event.getId()), any(UUID.class), eq(3), contains("still down")))
                .thenReturn(1);

        publisher.publishPending();

        verify(repository).markDeadIfLocked(eq(event.getId()), any(UUID.class), eq(3), contains("still down"));
    }

    @Test
    void emptyBatch_noKafkaSend_noSave() {
        when(repository.claimBatch(anyString(), any(UUID.class), eq(100))).thenReturn(List.of());
        // никаких send/save
        lenient().when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(null);

        publisher.publishPending();

        verify(kafkaTemplate, org.mockito.Mockito.never()).send(anyString(), anyString(), any());
        verify(repository, org.mockito.Mockito.never()).save(any());
        verify(repository, org.mockito.Mockito.never()).markPublishedIfLocked(any(), any(), any());
    }

    private OutboxEvent pending(int retryCount) {
        OutboxEvent e = new OutboxEvent();
        e.setId(UUID.randomUUID());
        e.setAggregateId(UUID.randomUUID());
        e.setEventType("TestEvent");
        e.setTopic("test.topic");
        e.setEventKey(UUID.randomUUID().toString());
        e.setPayload("{\"a\":1}");
        e.setStatus(OutboxStatus.PENDING);
        e.setRetryCount(retryCount);
        e.setNextAttemptAt(Instant.now());
        return e;
    }
}
