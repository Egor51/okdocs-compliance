package io.okdocs.compliance.persistence.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class EmailSubscription {
    @Id
    private UUID id;
    @Column(name = "user_id") private Long userId;
    @Column(nullable = false) private String email;
    @Column(name = "normalized_email", nullable = false, unique = true) private String normalizedEmail;
    @Column(nullable = false, length = 10) private String locale;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private EmailSubscriptionStatus status;
    @Column(nullable = false, length = 40) private String source;
    @Column(name = "consent_at", nullable = false) private Instant consentAt;
    @Column(name = "consent_ip", length = 45) private String consentIp;
    @Column(name = "unsubscribed_at") private Instant unsubscribedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void prePersist() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
}
