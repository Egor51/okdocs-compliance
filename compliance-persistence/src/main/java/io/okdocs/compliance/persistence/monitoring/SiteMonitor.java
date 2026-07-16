package io.okdocs.compliance.persistence.monitoring;

import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SiteMonitorStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "site_monitors")
@Getter
@Setter
@NoArgsConstructor
public class SiteMonitor {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "site_url", nullable = false, columnDefinition = "text")
    private String siteUrl;

    @Column(name = "site_domain", nullable = false)
    private String siteDomain;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_jurisdiction", nullable = false, length = 30)
    private ScanJurisdiction jurisdiction;

    @Column(name = "report_locale", nullable = false, length = 16)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private SiteMonitorStatus status;

    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(name = "notifications_enabled", nullable = false)
    private boolean notificationsEnabled;

    @Column(name = "last_scan_id")
    private UUID lastScanId;

    @Column(name = "last_score")
    private Integer lastScore;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by", length = 120)
    private String lockedBy;

    @Column(name = "lock_token")
    private UUID lockToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
