package io.okdocs.compliance.persistence.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mail_outbox")
@Getter
@Setter
@NoArgsConstructor
public class MailOutboxMessage {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "mail_type", nullable = false, length = 40)
    private String mailType;

    @Column(name = "aggregate_id", length = 100)
    private String aggregateId;

    @Column(nullable = false, length = 255)
    private String recipient;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(name = "template_name", nullable = false, length = 100)
    private String templateName;

    @Column(nullable = false, length = 10)
    private String locale;

    @Column(name = "model_payload", nullable = false, columnDefinition = "text")
    private String modelPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MailOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "lock_token")
    private UUID lockToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "purged_at")
    private Instant purgedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
        if (status == null) {
            status = MailOutboxStatus.PENDING;
        }
    }
}
