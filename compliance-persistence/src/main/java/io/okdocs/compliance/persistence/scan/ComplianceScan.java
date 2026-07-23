package io.okdocs.compliance.persistence.scan;

import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanLaunchSource;
import io.okdocs.compliance.contracts.enums.ScanFailureCode;
import io.okdocs.compliance.contracts.enums.ScanFailureStage;
import io.okdocs.compliance.contracts.enums.ScanFetchMode;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.scan.ScanFailure;
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
@Table(name = "compliance_scans")
@Getter
@Setter
@NoArgsConstructor
public class ComplianceScan {

    @Id
    private UUID id;

    /** null для гостей. */
    @Column(name = "user_id")
    private Long userId;

    /** UUID из guest JWT (для гостей). */
    @Column(name = "guest_id")
    private UUID guestId;

    /** Self-ref: предыдущий скан того же домена (повторная проверка). */
    @Column(name = "parent_scan_id")
    private UUID parentScanId;

    /** Recurring monitor that created this scan; null for manual/free flows. */
    @Column(name = "monitor_id")
    private UUID monitorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "launch_source", nullable = false, length = 20)
    private ScanLaunchSource launchSource = ScanLaunchSource.MANUAL;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ScanStatus status;

    @Column(name = "site_url", nullable = false, columnDefinition = "text")
    private String siteUrl;

    @Column(name = "site_domain", nullable = false)
    private String siteDomain;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "progress_step", length = 100)
    private String progressStep;

    @Column(name = "progress_pct", nullable = false)
    private int progressPct;

    @Column(name = "score")
    private Integer score;

    @Column(name = "pages_scanned", nullable = false)
    private int pagesScanned;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ScanTier tier;

    /** Продуктовый flow: FREE_MARKETING (static, 1 страница) vs CABINET_PREMIUM (static+dynamic). */
    @Enumerated(EnumType.STRING)
    @Column(name = "scan_kind", nullable = false, length = 30)
    private ScanKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_jurisdiction", nullable = false, length = 30)
    private ScanJurisdiction jurisdiction;

    /**
     * Язык отчёта (locale пользователя, напр. {@code ru}/{@code en}/{@code de}). Ось локализации
     * evidence/message ≠ jurisdiction (UK-скан английский, но немец на DE хочет немецкий). Worker
     * рендерит локализуемые тексты по этому значению. nullable для legacy-сканов (worker → дефолт ru).
     */
    @Column(name = "report_locale", length = 16)
    private String locale;

    /** Лимит страниц краула (перенесён из события — worker читает из БД). */
    @Column(name = "max_pages", nullable = false)
    private int maxPages;

    /** Для CABINET_PREMIUM dynamic обязателен: CDP недоступен → FAILED + refund. */
    @Column(name = "dynamic_required", nullable = false)
    private boolean dynamicRequired;

    @Column(name = "buyer_email")
    private String buyerEmail;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 50)
    private ScanFailureCode failureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_stage", length = 30)
    private ScanFailureStage failureStage;

    @Column(name = "failure_retryable")
    private Boolean failureRetryable;

    @Column(name = "failure_http_status")
    private Integer failureHttpStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_fetch_mode", length = 20)
    private ScanFetchMode failureFetchMode;

    @Column(name = "failure_fallback_attempted")
    private Boolean failureFallbackAttempted;

    @Column(name = "failure_incident_id")
    private UUID failureIncidentId;

    /** JSON: слияние метрик краулера и ошибок правил. */
    @Column(name = "diagnostics_json", columnDefinition = "text")
    private String diagnosticsJson;

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
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    /** Перевод в FAILED — используется reaper'ом и пайплайном. */
    public void fail(String message, ScanFailure failure) {
        this.status = ScanStatus.FAILED;
        this.errorMessage = message;
        setFailure(failure);
        this.finishedAt = Instant.now();
    }

    public void setFailure(ScanFailure failure) {
        if (failure == null) {
            failureCode = null;
            failureStage = null;
            failureRetryable = null;
            failureHttpStatus = null;
            failureFetchMode = null;
            failureFallbackAttempted = null;
            failureIncidentId = null;
            return;
        }
        failureCode = failure.code();
        failureStage = failure.stage();
        failureRetryable = failure.retryable();
        failureHttpStatus = failure.httpStatus();
        failureFetchMode = failure.fetchMode();
        failureFallbackAttempted = failure.fallbackAttempted();
        failureIncidentId = failure.incidentId();
    }

    public ScanFailure failure() {
        if (failureCode == null) {
            return null;
        }
        return new ScanFailure(
                failureCode,
                failureStage == null ? ScanFailureStage.UNKNOWN : failureStage,
                Boolean.TRUE.equals(failureRetryable),
                failureHttpStatus,
                failureFetchMode,
                Boolean.TRUE.equals(failureFallbackAttempted),
                failureIncidentId);
    }
}
