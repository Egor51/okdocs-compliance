package io.okdocs.compliance.persistence.auth;

import io.okdocs.compliance.contracts.enums.OAuthProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Связка внешней OAuth-личности с локальным {@link AppUser} (F.2 §F7).
 * <p>
 * Одна внешняя личность ({@code provider} + {@code providerUserId}) уникальна — повторный вход тем же
 * соц-аккаунтом находит существующую связку, а не создаёт дубль. {@code emailVerified} — флаг от
 * провайдера, на основании которого решается безопасная auto-link к существующему аккаунту по email.
 */
@Entity
@Table(name = "oauth_identities",
        uniqueConstraints = @UniqueConstraint(name = "uq_oauth_provider_user",
                columnNames = {"provider", "provider_user_id"}))
@Getter
@Setter
@NoArgsConstructor
public class OAuthIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OAuthProvider provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
