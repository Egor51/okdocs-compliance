package io.okdocs.compliance.persistence.remediation;

import io.okdocs.compliance.contracts.remediation.RemediationRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface RemediationLeadRepository extends JpaRepository<RemediationLead, UUID> {

    Optional<RemediationLead> findFirstByContactEmailIgnoreCaseAndSiteDomainAndStatusInOrderByCreatedAtDesc(
            String email, String siteDomain, Collection<RemediationRequestStatus> statuses);

    /** Повторный submit/сетевой retry не создаёт вторую активную заявку по тому же домену. */
    @Modifying
    @Query(value = """
            INSERT INTO remediation_leads
                (id, site_url, site_domain, contact_name, contact_email, contact_phone,
                 locale, status, consent_at, ip_address, created_at, updated_at)
            VALUES
                (:id, :siteUrl, :siteDomain, :name, :email, :phone,
                 :locale, :status, :consentAt, :ipAddress, :now, :now)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("siteUrl") String siteUrl,
                       @Param("siteDomain") String siteDomain,
                       @Param("name") String name,
                       @Param("email") String email,
                       @Param("phone") String phone,
                       @Param("locale") String locale,
                       @Param("status") String status,
                       @Param("consentAt") Instant consentAt,
                       @Param("ipAddress") String ipAddress,
                       @Param("now") Instant now);
}
