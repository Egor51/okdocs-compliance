package io.okdocs.compliance.contracts.cabinet;

import io.okdocs.compliance.contracts.enums.BalanceTxnSource;
import io.okdocs.compliance.contracts.enums.BalanceTxnType;

import java.time.Instant;
import java.util.UUID;

/**
 * Движение в леджере баланса. {@code siteDomain} денормализован (для DEBIT/REFUND — домен скана),
 * чтобы UI леджера не делал N+1 запросов. {@code source} — карман для DEBIT/REFUND
 * (MONTHLY/PURCHASED), {@code null} для остальных типов.
 */
public record BalanceTransactionDto(
        UUID id,
        BalanceTxnType type,
        BalanceTxnSource source,
        int amount,
        int balanceAfter,
        UUID scanId,
        String siteDomain,
        String note,
        Instant createdAt
) {
}
