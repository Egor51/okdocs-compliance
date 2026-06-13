package io.okdocs.compliance.persistence.outbox;

import io.okdocs.compliance.contracts.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Для метрики observability: сколько событий в данном статусе (PENDING/DEAD gauge). */
    long countByStatus(OutboxStatus status);

    /**
     * Атомарный захват-и-выборка батча готовых к публикации событий (§4.5).
     * {@code FOR UPDATE SKIP LOCKED} исключает двойную публикацию при нескольких инстансах
     * (api + worker). Lock lease 2 минуты: протухший lock умершего инстанса переподбирается.
     * JPQL не умеет {@code SKIP LOCKED}, поэтому native {@code UPDATE ... RETURNING *}.
     * <p>
     * Без {@code @Modifying}: запрос возвращает строки (захваченные события), а не update-count.
     * {@code @Modifying} в Spring Data ожидает void/int/boolean и конфликтует с {@code List<Entity>};
     * Hibernate 6 на PostgreSQL отдаёт {@code RETURNING *} как selecting-запрос. Вызывать внутри
     * короткой {@code @Transactional}: после claim транзакция закрывается, Kafka publish идёт вне
     * DB-транзакции, а финальный update fenced по {@code lock_token}.
     * <p>
     * <b>TZ-safety:</b> временные колонки — naive {@code TIMESTAMP}, а Hibernate пишет {@code Instant}
     * в UTC. Поэтому сравниваем с {@code now() AT TIME ZONE 'UTC'} (naive UTC), а не с голым
     * {@code now()} ({@code timestamptz}): иначе PostgreSQL приводил бы {@code TIMESTAMP} к
     * {@code timestamptz} по TZ JDBC-сессии (по умолчанию = TZ JVM), и при ненулевом смещении
     * фильтр {@code next_attempt_at <= now()} ловил бы будущие/чужие события. Сессия-зависимый баг.
     */
    @Query(value = """
            UPDATE outbox_events
            SET locked_at = (now() AT TIME ZONE 'UTC'),
                locked_by = :instanceId,
                lock_token = :lockToken
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE status = 'PENDING'
                  AND next_attempt_at <= (now() AT TIME ZONE 'UTC')
                  AND (locked_at IS NULL
                       OR locked_at < (now() AT TIME ZONE 'UTC') - interval '2 minutes')
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            RETURNING *
            """, nativeQuery = true)
    List<OutboxEvent> claimBatch(@Param("instanceId") String instanceId,
                                 @Param("lockToken") UUID lockToken,
                                 @Param("limit") int limit);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET status = 'PUBLISHED',
                published_at = :publishedAt,
                locked_at = NULL,
                locked_by = NULL,
                lock_token = NULL
            WHERE id = :id
              AND status = 'PENDING'
              AND lock_token = :lockToken
            """, nativeQuery = true)
    int markPublishedIfLocked(@Param("id") UUID id,
                              @Param("lockToken") UUID lockToken,
                              @Param("publishedAt") Instant publishedAt);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET retry_count = :retryCount,
                last_error = :lastError,
                next_attempt_at = :nextAttemptAt,
                locked_at = NULL,
                locked_by = NULL,
                lock_token = NULL
            WHERE id = :id
              AND status = 'PENDING'
              AND lock_token = :lockToken
            """, nativeQuery = true)
    int markRetryIfLocked(@Param("id") UUID id,
                          @Param("lockToken") UUID lockToken,
                          @Param("retryCount") int retryCount,
                          @Param("lastError") String lastError,
                          @Param("nextAttemptAt") Instant nextAttemptAt);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET status = 'DEAD',
                retry_count = :retryCount,
                last_error = :lastError,
                locked_at = NULL,
                locked_by = NULL,
                lock_token = NULL
            WHERE id = :id
              AND status = 'PENDING'
              AND lock_token = :lockToken
            """, nativeQuery = true)
    int markDeadIfLocked(@Param("id") UUID id,
                         @Param("lockToken") UUID lockToken,
                         @Param("retryCount") int retryCount,
                         @Param("lastError") String lastError);
}
