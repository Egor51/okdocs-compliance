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
@Table(name = "remediation_leads")
@Getter
@Setter
@NoArgsConstructor
public class RemediationLead {

    @Id
    private UUID id;

    @Column(name = "site_url", nullable = false, length = 2048)
    private String siteUrl;

    @Column(name = "site_domain", nullable = false, length = 255)
    private String siteDomain;

    @Column(name = "contact_name", nullable = false, length = 100)
    private String contactName;

    @Column(name = "contact_email", nullable = false, length = 254)
    private String contactEmail;

    @Column(name = "contact_phone", length = 40)
    private String contactPhone;

    @Column(nullable = false, length = 16)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RemediationRequestStatus status;

    @Column(name = "consent_at", nullable = false)
    private Instant consentAt;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
