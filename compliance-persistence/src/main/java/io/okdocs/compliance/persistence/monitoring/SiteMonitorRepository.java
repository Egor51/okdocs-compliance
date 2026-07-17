package io.okdocs.compliance.persistence.monitoring;

import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SiteMonitorRepository extends JpaRepository<SiteMonitor, UUID> {

    List<SiteMonitor> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<SiteMonitor> findByIdAndUserId(UUID id, Long userId);

    boolean existsByUserIdAndSiteDomainAndJurisdiction(Long userId, String domain,
                                                        ScanJurisdiction jurisdiction);

    long countByUserId(Long userId);

    /** Multi-replica-safe lease claim, following the transactional outbox pattern. */
    @Query(value = """
            UPDATE site_monitors
            SET locked_at = (now() AT TIME ZONE 'UTC'),
                locked_by = :instanceId,
                lock_token = :lockToken,
                updated_at = (now() AT TIME ZONE 'UTC')
            WHERE id IN (
                SELECT id FROM site_monitors
                WHERE status = 'ACTIVE'
                  AND next_run_at <= (now() AT TIME ZONE 'UTC')
                  AND (locked_at IS NULL
                       OR locked_at < (now() AT TIME ZONE 'UTC') - interval '5 minutes')
                ORDER BY next_run_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            RETURNING *
            """, nativeQuery = true)
    List<SiteMonitor> claimDue(@Param("instanceId") String instanceId,
                               @Param("lockToken") UUID lockToken,
                               @Param("limit") int limit);

    @Modifying
    @Query("""
            UPDATE SiteMonitor m
            SET m.lockedAt = null, m.lockedBy = null, m.lockToken = null
            WHERE m.id = :id AND m.lockToken = :lockToken
            """)
    int releaseLease(@Param("id") UUID id, @Param("lockToken") UUID lockToken);

    @Modifying
    @Query("""
            UPDATE SiteMonitor m
            SET m.status = :status, m.nextRunAt = :nextRunAt,
                m.lockedAt = null, m.lockedBy = null, m.lockToken = null
            WHERE m.id = :id AND m.lockToken = :lockToken
            """)
    int finishClaim(@Param("id") UUID id,
                    @Param("lockToken") UUID lockToken,
                    @Param("status") io.okdocs.compliance.contracts.enums.SiteMonitorStatus status,
                    @Param("nextRunAt") Instant nextRunAt);
}
