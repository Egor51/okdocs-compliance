package io.okdocs.compliance.persistence.scan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScanEmailRepository extends JpaRepository<ScanEmail, UUID> {

    List<ScanEmail> findByScanId(UUID scanId);
}
