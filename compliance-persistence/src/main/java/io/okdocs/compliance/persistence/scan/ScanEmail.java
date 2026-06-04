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

@Entity
@Table(name = "scan_emails")
@Getter
@Setter
@NoArgsConstructor
public class ScanEmail {

    @Id
    private UUID id;

    @Column(name = "scan_id", nullable = false)
    private UUID scanId;

    @Column(nullable = false)
    private String email;

    @Column(name = "consent_to_processing", nullable = false)
    private boolean consentToProcessing;

    @Column(name = "consent_to_marketing", nullable = false)
    private boolean consentToMarketing;

    @Column(name = "consent_ip", nullable = false, length = 45)
    private String consentIp;

    @Column(name = "consent_at", nullable = false)
    private Instant consentAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (consentAt == null) {
            consentAt = Instant.now();
        }
    }
}
