package io.okdocs.compliance.persistence.scan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ComplianceScanReportRepository extends JpaRepository<ComplianceScanReport, UUID> {
}
