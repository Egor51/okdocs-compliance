package io.okdocs.compliance.persistence.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Атомарный захват-и-выборка батча готовых к публикации событий (§4.5).
     * {@code FOR UPDATE SKIP LOCKED} исключает двойную публикацию при нескольких инстансах
     * (api + worker). Lock lease 2 минуты: протухший lock умершего инстанса переподбирается.
     * JPQL не умеет {@code SKIP LOCKED}, поэтому native {@code UPDATE ... RETURNING *}.
     * <p>
     * Без {@code @Modifying}: запрос возвращает строки (захваченные события), а не update-count.
     * {@code @Modifying} в Spring Data ожидает void/int/boolean и конфликтует с {@code List<Entity>};
     * Hibernate 6 на PostgreSQL отдаёт {@code RETURNING *} как selecting-запрос. Вызывать строго
     * внутри {@code @Transactional} — захват и публикация должны быть в одной транзакции.
     */
    @Query(value = """
            UPDATE outbox_events
            SET locked_at = now(), locked_by = :instanceId
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE status = 'PENDING'
                  AND next_attempt_at <= now()
                  AND (locked_at IS NULL OR locked_at < now() - interval '2 minutes')
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            RETURNING *
            """, nativeQuery = true)
    List<OutboxEvent> lockBatch(@Param("instanceId") String instanceId, @Param("limit") int limit);
}
