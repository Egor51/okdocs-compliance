package io.okdocs.compliance.persistence.scan;

import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
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
@Table(name = "compliance_findings")
@Getter
@Setter
@NoArgsConstructor
public class ComplianceFinding {

    @Id
    private UUID id;

    @Column(name = "scan_id", nullable = false)
    private UUID scanId;

    @Column(nullable = false, length = 60)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FindingSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private FindingCategory category;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(columnDefinition = "text")
    private String evidence;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 30)
    private SourceType sourceType;

    @Column(name = "fine_amount")
    private String fineAmount;

    @Column(name = "fine_authority", length = 100)
    private String fineAuthority;

    @Column(name = "legal_basis")
    private String legalBasis;

    @Column(columnDefinition = "text")
    private String explanation;

    @Column(columnDefinition = "text")
    private String recommendation;

    @Column
    private Double confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", length = 30)
    private VerificationStatus verificationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", length = 30)
    private EvidenceType evidenceType;

    @Column(name = "matched_signals", columnDefinition = "text")
    private String matchedSignals;

    @Column(name = "page_url", columnDefinition = "text")
    private String pageUrl;

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
