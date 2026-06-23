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

/**
 * One-time код автологина после OAuth-callback'а (F.8). Хранится как SHA-256 hash (plain не храним).
 * Одноразовый ({@code consumedAt}) и короткоживущий ({@code expiresAt}).
 */
@Entity
@Table(name = "oauth_login_codes")
@Getter
@Setter
@NoArgsConstructor
public class OAuthLoginCode {

    @Id
    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
