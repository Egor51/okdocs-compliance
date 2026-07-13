package io.okdocs.compliance.persistence.scan;

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

/**
 * Готовый снапшот отчёта скана (§4.2): worker сериализует два {@code ScanReportResponse}
 * (premium + free) в той же транзакции, что findings/status/outbox. PK = {@code scanId},
 * {@code ON DELETE CASCADE} от {@code compliance_scans} — TTL-чистка подхватывает snapshot.
 * API отдаёт нужный JSON passthrough-ом по {@code effectiveTier}, без доменной логики.
 */
@Entity
@Table(name = "compliance_scan_reports")
@Getter
@Setter
@NoArgsConstructor
public class ComplianceScanReport {

    @Id
    @Column(name = "scan_id")
    private UUID scanId;

    @Column(name = "report_schema_version", nullable = false)
    private int reportSchemaVersion = 2;

    @Column(name = "premium_report_json", nullable = false, columnDefinition = "text")
    private String premiumReportJson;

    @Column(name = "free_report_json", nullable = false, columnDefinition = "text")
    private String freeReportJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
