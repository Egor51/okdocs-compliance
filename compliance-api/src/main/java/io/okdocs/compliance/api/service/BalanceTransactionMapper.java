package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.cabinet.BalanceTransactionDto;
import io.okdocs.compliance.persistence.billing.ScanBalanceTransaction;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Маппинг леджера {@link ScanBalanceTransaction} → {@link BalanceTransactionDto} с денормализацией
 * {@code siteDomain} (для DEBIT/REFUND) одним batch-запросом сканов — без N+1 (§1.9).
 */
@Component
@RequiredArgsConstructor
public class BalanceTransactionMapper {

    private final ComplianceScanRepository scanRepository;

    public List<BalanceTransactionDto> toDtos(List<ScanBalanceTransaction> txns) {
        if (txns.isEmpty()) {
            return List.of();
        }
        List<UUID> scanIds = txns.stream()
                .map(ScanBalanceTransaction::getScanId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, String> domains = scanIds.isEmpty()
                ? Map.of()
                : scanRepository.findAllById(scanIds).stream()
                        .collect(Collectors.toMap(ComplianceScan::getId, ComplianceScan::getSiteDomain,
                                (a, b) -> a));
        return txns.stream().map(t -> toDto(t, domains)).toList();
    }

    private BalanceTransactionDto toDto(ScanBalanceTransaction t, Map<UUID, String> domains) {
        String domain = t.getScanId() == null ? null : domains.get(t.getScanId());
        return new BalanceTransactionDto(
                t.getId(),
                t.getType(),
                t.getSource(),
                t.getAmount(),
                t.getBalanceAfter(),
                t.getScanId(),
                domain,
                t.getNote(),
                t.getCreatedAt());
    }
}
