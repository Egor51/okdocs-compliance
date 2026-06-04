package io.okdocs.compliance.persistence.scan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ComplianceFindingRepository extends JpaRepository<ComplianceFinding, UUID> {

    List<ComplianceFinding> findByScanIdOrderByCreatedAtAsc(UUID scanId);

    void deleteByScanId(UUID scanId);
}
