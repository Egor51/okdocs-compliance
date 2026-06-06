package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.scan.ScanListItemDto;
import io.okdocs.compliance.persistence.scan.ComplianceFindingRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Маппинг {@link ComplianceScan} → {@link ScanListItemDto} со сводкой по severity
 * ({@code criticalCount}/{@code highCount}). Подсчёт findings — одним групповым запросом на список
 * (без N+1), общий для истории, дашборда и админки.
 */
@Component
@RequiredArgsConstructor
public class ScanMapper {

    private final ComplianceFindingRepository findingRepository;

    /** Сводки {scanId → [critical, high]}. */
    public List<ScanListItemDto> toListItems(List<ComplianceScan> scans) {
        if (scans.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = scans.stream().map(ComplianceScan::getId).toList();
        Map<UUID, int[]> counts = severityCounts(ids);
        return scans.stream().map(s -> toListItem(s, counts.getOrDefault(s.getId(), new int[2]))).toList();
    }

    private Map<UUID, int[]> severityCounts(List<UUID> scanIds) {
        return findingRepository.countSeverityByScanIds(scanIds).stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> {
                            int[] cnt = new int[2];
                            FindingSeverity severity = (FindingSeverity) row[1];
                            long count = (long) row[2];
                            if (severity == FindingSeverity.CRITICAL) {
                                cnt[0] = (int) count;
                            } else if (severity == FindingSeverity.HIGH) {
                                cnt[1] = (int) count;
                            }
                            return cnt;
                        },
                        (a, b) -> {
                            a[0] += b[0];
                            a[1] += b[1];
                            return a;
                        }));
    }

    private ScanListItemDto toListItem(ComplianceScan s, int[] counts) {
        return new ScanListItemDto(
                s.getId(),
                s.getSiteUrl(),
                s.getSiteDomain(),
                s.getStatus(),
                s.getScore(),
                s.getTier(),
                counts[0],
                counts[1],
                s.getParentScanId(),
                s.getCreatedAt(),
                s.getFinishedAt());
    }
}
