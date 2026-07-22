package io.okdocs.compliance.persistence.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Одна цепочка ротаций = одна сессия/устройство. */
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Следующий токен цепочки; позволяет идемпотентно ответить на конкурентный refresh. */
    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "rotation_grace_until")
    private Instant rotationGraceUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "user_agent", columnDefinition = "text")
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (familyId == null) {
            familyId = id;
        }
        createdAt = Instant.now();
    }
}
