package io.okdocs.compliance.persistence.jurisdiction;

import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JurisdictionCatalogRepository extends JpaRepository<JurisdictionCatalog, Long> {

    List<JurisdictionCatalog> findByActiveTrueOrderBySortOrderAsc();

    Optional<JurisdictionCatalog> findByCodeAndActiveTrue(ScanJurisdiction code);
}
