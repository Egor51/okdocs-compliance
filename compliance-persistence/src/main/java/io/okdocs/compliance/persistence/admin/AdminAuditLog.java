package io.okdocs.compliance.persistence.admin;

import io.okdocs.compliance.contracts.enums.AdminActionType;
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

/** Append-only журнал действий админа: кто/что/над кем/почему. */
@Entity
@Table(name = "admin_audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AdminAuditLog {

    @Id
    private UUID id;

    @Column(name = "admin_user_id", nullable = false)
    private Long adminUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AdminActionType action;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "details_json", columnDefinition = "text")
    private String detailsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }
}
