package io.okdocs.compliance.persistence.mail;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MailOutboxRepository extends JpaRepository<MailOutboxMessage, UUID> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<MailOutboxMessage> findByIdempotencyKey(String idempotencyKey);

    long countByStatus(MailOutboxStatus status);

    @Modifying
    @Query(value = """
            INSERT INTO mail_outbox (
                id, idempotency_key, mail_type, aggregate_id, recipient, subject,
                template_name, locale, model_payload, status, attempt_count,
                next_attempt_at, created_at)
            VALUES (
                :id, :idempotencyKey, :mailType, :aggregateId, :recipient, :subject,
                :templateName, :locale, :modelPayload, 'PENDING', 0,
                :createdAt, :createdAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertPending(@Param("id") UUID id,
                      @Param("idempotencyKey") String idempotencyKey,
                      @Param("mailType") String mailType,
                      @Param("aggregateId") String aggregateId,
                      @Param("recipient") String recipient,
                      @Param("subject") String subject,
                      @Param("templateName") String templateName,
                      @Param("locale") String locale,
                      @Param("modelPayload") String modelPayload,
                      @Param("createdAt") Instant createdAt);

    @Query(value = """
            UPDATE mail_outbox
            SET locked_at = (now() AT TIME ZONE 'UTC'),
                locked_by = :instanceId,
                lock_token = :lockToken
            WHERE id IN (
                SELECT id FROM mail_outbox
                WHERE status = 'PENDING'
                  AND next_attempt_at <= (now() AT TIME ZONE 'UTC')
                  AND (locked_at IS NULL
                       OR locked_at < (now() AT TIME ZONE 'UTC') - interval '2 minutes')
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            RETURNING *
            """, nativeQuery = true)
    List<MailOutboxMessage> claimBatch(@Param("instanceId") String instanceId,
                                       @Param("lockToken") UUID lockToken,
                                       @Param("limit") int limit);

    @Modifying
    @Query(value = """
            UPDATE mail_outbox
            SET status = :status,
                sent_at = :sentAt,
                locked_at = NULL,
                locked_by = NULL,
                lock_token = NULL
            WHERE id = :id AND status = 'PENDING' AND lock_token = :lockToken
            """, nativeQuery = true)
    int markDeliveredIfLocked(@Param("id") UUID id,
                              @Param("lockToken") UUID lockToken,
                              @Param("status") String status,
                              @Param("sentAt") Instant sentAt);

    @Modifying
    @Query(value = """
            UPDATE mail_outbox
            SET attempt_count = :attemptCount,
                last_error = :lastError,
                next_attempt_at = :nextAttemptAt,
                locked_at = NULL,
                locked_by = NULL,
                lock_token = NULL
            WHERE id = :id AND status = 'PENDING' AND lock_token = :lockToken
            """, nativeQuery = true)
    int markRetryIfLocked(@Param("id") UUID id,
                          @Param("lockToken") UUID lockToken,
                          @Param("attemptCount") int attemptCount,
                          @Param("lastError") String lastError,
                          @Param("nextAttemptAt") Instant nextAttemptAt);

    @Modifying
    @Query(value = """
            UPDATE mail_outbox
            SET status = 'DEAD',
                attempt_count = :attemptCount,
                last_error = :lastError,
                locked_at = NULL,
                locked_by = NULL,
                lock_token = NULL
            WHERE id = :id AND status = 'PENDING' AND lock_token = :lockToken
            """, nativeQuery = true)
    int markDeadIfLocked(@Param("id") UUID id,
                         @Param("lockToken") UUID lockToken,
                         @Param("attemptCount") int attemptCount,
                         @Param("lastError") String lastError);

    @Modifying
    @Query(value = """
            UPDATE mail_outbox
            SET model_payload = '', purged_at = :purgedAt
            WHERE status IN ('SENT', 'SIMULATED', 'CANCELLED')
              AND sent_at < :cutoff
              AND purged_at IS NULL
            """, nativeQuery = true)
    int purgeDeliveredPayloads(@Param("cutoff") Instant cutoff,
                               @Param("purgedAt") Instant purgedAt);
}
