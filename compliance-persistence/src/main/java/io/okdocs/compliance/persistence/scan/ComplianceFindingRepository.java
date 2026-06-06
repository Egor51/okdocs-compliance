package io.okdocs.compliance.persistence.scan;

import io.okdocs.compliance.contracts.enums.FindingSeverity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ComplianceFindingRepository extends JpaRepository<ComplianceFinding, UUID> {

    List<ComplianceFinding> findByScanIdOrderByCreatedAtAsc(UUID scanId);

    void deleteByScanId(UUID scanId);

    /**
     * Подсчёт findings по severity для набора сканов — для сводки {@code criticalCount}/{@code highCount}
     * в истории/дашборде без N+1. Возвращает строки {@code [scanId, severity, count]}.
     */
    @Query("""
            SELECT f.scanId, f.severity, COUNT(f)
            FROM ComplianceFinding f
            WHERE f.scanId IN :scanIds
            GROUP BY f.scanId, f.severity
            """)
    List<Object[]> countSeverityByScanIds(@Param("scanIds") Collection<UUID> scanIds);

    long countByScanIdAndSeverity(UUID scanId, FindingSeverity severity);
}
