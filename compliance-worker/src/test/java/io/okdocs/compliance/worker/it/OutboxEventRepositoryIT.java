package io.okdocs.compliance.worker.it;

import io.okdocs.compliance.contracts.enums.OutboxStatus;
import io.okdocs.compliance.persistence.outbox.OutboxEvent;
import io.okdocs.compliance.persistence.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT на {@link OutboxEventRepository#lockBatch} — native {@code UPDATE ... FROM (SELECT ... FOR
 * UPDATE SKIP LOCKED) RETURNING *}. Проверяет ровно то, что нельзя проверить на H2:
 * <ul>
 *   <li>захват только PENDING с {@code next_attempt_at <= now} и не-залоченных;</li>
 *   <li>протухший lock (старше 2 минут) переподбирается;</li>
 *   <li>SKIP LOCKED: два конкурентных publisher'а не получают пересекающихся строк.</li>
 * </ul>
 */
// SKIP LOCKED проверяется на РЕАЛЬНО закоммиченных строках в параллельных транзакциях. @SpringBootTest
// (без @Transactional) не оборачивает тест в откатываемую tx, поэтому saveAndFlush коммитит сразу и
// lockBatch в отдельной tx их видит. Чистим вручную в @BeforeEach.
@SpringBootTest(classes = PersistenceItConfig.class)
class OutboxEventRepositoryIT extends AbstractPostgresIT {

    @Autowired
    OutboxEventRepository repository;

    @Autowired
    TransactionTemplate txTemplate;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void lockBatch_capturesOnlyReadyPendingEvents() {
        OutboxEvent ready = save(OutboxStatus.PENDING, Instant.now().minusSeconds(1), null, null);
        save(OutboxStatus.PENDING, Instant.now().plus(1, ChronoUnit.HOURS), null, null); // future
        save(OutboxStatus.PUBLISHED, Instant.now().minusSeconds(1), null, null);          // not pending
        save(OutboxStatus.DEAD, Instant.now().minusSeconds(1), null, null);               // not pending

        List<OutboxEvent> locked = txTemplate.execute(s -> repository.lockBatch("inst-A", 100));

        assertThat(locked).extracting(OutboxEvent::getId).containsExactly(ready.getId());
        assertThat(locked.get(0).getLockedBy()).isEqualTo("inst-A");
        assertThat(locked.get(0).getLockedAt()).isNotNull();
    }

    @Test
    void lockBatch_reclaimsStaleLocks() {
        // Залочено другим инстансом 3 минуты назад (> 2 мин lease) → должно переподобраться.
        OutboxEvent stale = save(OutboxStatus.PENDING, Instant.now().minusSeconds(1),
                Instant.now().minus(3, ChronoUnit.MINUTES), "dead-instance");
        // Свежий lock (30с назад) — НЕ трогаем.
        save(OutboxStatus.PENDING, Instant.now().minusSeconds(1),
                Instant.now().minusSeconds(30), "live-instance");

        List<OutboxEvent> locked = txTemplate.execute(s -> repository.lockBatch("inst-A", 100));

        assertThat(locked).extracting(OutboxEvent::getId).containsExactly(stale.getId());
        assertThat(locked.get(0).getLockedBy()).isEqualTo("inst-A");
    }

    @Test
    void lockBatch_twoPublishers_skipLocked_noOverlap() throws Exception {
        IntStream.range(0, 20).forEach(i ->
                save(OutboxStatus.PENDING, Instant.now().minusSeconds(1), null, null));

        // Два publisher'а в параллельных транзакциях захватывают батчи одновременно. FOR UPDATE
        // SKIP LOCKED гарантирует непересекающиеся множества id.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Set<UUID>> a = CompletableFuture.supplyAsync(
                    () -> lockIdsInTx("inst-A", 10), pool);
            CompletableFuture<Set<UUID>> b = CompletableFuture.supplyAsync(
                    () -> lockIdsInTx("inst-B", 10), pool);

            Set<UUID> idsA = a.get();
            Set<UUID> idsB = b.get();

            assertThat(idsA).isNotEmpty();
            assertThat(idsB).isNotEmpty();
            assertThat(idsA).doesNotContainAnyElementsOf(idsB);
            assertThat(idsA.size() + idsB.size()).isLessThanOrEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Захват батча в отдельной транзакции, держим её открытой пока второй поток тоже захватывает. */
    private Set<UUID> lockIdsInTx(String instance, int limit) {
        return txTemplate.execute(s -> {
            List<OutboxEvent> locked = repository.lockBatch(instance, limit);
            Set<UUID> ids = locked.stream().map(OutboxEvent::getId).collect(Collectors.toSet());
            // Небольшая пауза, чтобы транзакции реально пересеклись во времени и SKIP LOCKED сработал.
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ids;
        });
    }

    private OutboxEvent save(OutboxStatus status, Instant nextAttemptAt, Instant lockedAt, String lockedBy) {
        OutboxEvent e = new OutboxEvent();
        e.setAggregateId(UUID.randomUUID());
        e.setEventType("TestEvent");
        e.setTopic("test.topic");
        e.setEventKey(UUID.randomUUID().toString());
        e.setPayload("{}");
        e.setStatus(status);
        e.setNextAttemptAt(nextAttemptAt);
        e.setLockedAt(lockedAt);
        e.setLockedBy(lockedBy);
        return repository.saveAndFlush(e);
    }
}
