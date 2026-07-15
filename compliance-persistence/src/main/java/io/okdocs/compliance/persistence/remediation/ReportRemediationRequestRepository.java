package io.okdocs.compliance.persistence.remediation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ReportRemediationRequestRepository
        extends JpaRepository<ReportRemediationRequest, UUID> {

    Optional<ReportRemediationRequest> findByScanIdAndUserId(UUID scanId, Long userId);

    /** ON CONFLICT делает повторный клик и параллельные вкладки идемпотентными. */
    @Modifying
    @Query(value = """
            INSERT INTO report_remediation_requests
                (id, scan_id, user_id, site_url_snapshot, customer_email_snapshot,
                 status, locale, created_at, updated_at)
            VALUES
                (:id, :scanId, :userId, :siteUrl, :email,
                 :status, :locale, :now, :now)
            ON CONFLICT (scan_id, user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("scanId") UUID scanId,
                       @Param("userId") Long userId,
                       @Param("siteUrl") String siteUrl,
                       @Param("email") String email,
                       @Param("status") String status,
                       @Param("locale") String locale,
                       @Param("now") Instant now);
}
