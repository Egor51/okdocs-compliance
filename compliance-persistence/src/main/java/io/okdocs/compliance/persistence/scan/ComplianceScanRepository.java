package io.okdocs.compliance.persistence.scan;

import io.okdocs.compliance.contracts.enums.ScanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ComplianceScanRepository extends JpaRepository<ComplianceScan, UUID> {

    Page<ComplianceScan> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<ComplianceScan> findByGuestIdOrderByCreatedAtDesc(UUID guestId, Pageable pageable);

    List<ComplianceScan> findByStatus(ScanStatus status);

    /** Rate limit: сколько сканов с этого IP за окно. */
    List<ComplianceScan> findByIpAddressAndCreatedAtAfter(String ipAddress, Instant after);

    /** Reaper зависших сканов (§5.3): статус в работе + давно не обновлялся. */
    List<ComplianceScan> findByStatusInAndUpdatedAtBefore(Collection<ScanStatus> statuses, Instant cutoff);

    /** История кабинета с опциональными фильтрами domain (ILIKE substring) и status (§4.1). */
    @Query("""
            SELECT s FROM ComplianceScan s
            WHERE s.userId = :userId
              AND (:domain IS NULL OR lower(s.siteDomain) LIKE lower(concat('%', :domain, '%')))
              AND (:status IS NULL OR s.status = :status)
            ORDER BY s.createdAt DESC
            """)
    Page<ComplianceScan> searchByUser(@Param("userId") Long userId,
                                      @Param("domain") String domain,
                                      @Param("status") ScanStatus status,
                                      Pageable pageable);

    /** TTL-чистка гостевых сканов (§4.6): userId IS NULL и старше cutoff. Cascade удалит findings/emails. */
    @Modifying
    @Query("DELETE FROM ComplianceScan s WHERE s.userId IS NULL AND s.createdAt < :cutoff")
    int deleteGuestScansOlderThan(@Param("cutoff") Instant cutoff);
}
