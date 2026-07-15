package io.okdocs.compliance.persistence.remediation;

import io.okdocs.compliance.contracts.remediation.RemediationRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "report_remediation_requests")
@Getter
@Setter
@NoArgsConstructor
public class ReportRemediationRequest {

    @Id
    private UUID id;

    @Column(name = "scan_id", nullable = false)
    private UUID scanId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "site_url_snapshot", nullable = false, length = 2048)
    private String siteUrlSnapshot;

    @Column(name = "customer_email_snapshot", nullable = false, length = 255)
    private String customerEmailSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RemediationRequestStatus status;

    @Column(nullable = false, length = 16)
    private String locale;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
