package io.okdocs.compliance.persistence.monitoring;

import org.springframework.data.jpa.repository.JpaRepository;
import io.okdocs.compliance.contracts.enums.MonitorRunStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonitorRunRepository extends JpaRepository<MonitorRun, UUID> {
    List<MonitorRun> findTop50ByMonitorIdOrderByCreatedAtDesc(UUID monitorId);
    Optional<MonitorRun> findByScanId(UUID scanId);
    boolean existsByMonitorIdAndScanId(UUID monitorId, UUID scanId);
    boolean existsByMonitorIdAndStatus(UUID monitorId, MonitorRunStatus status);
}
