package io.okdocs.compliance.contracts.cabinet;

import java.time.Instant;

/**
 * Баланс сканов юзера. {@code available = monthlyQuota − usedThisPeriod + purchasedRemaining}
 * Докупленные сканы и ручные корректировки отражаются в {@code purchasedRemaining}.
 */
public record ScanBalanceDto(
        int monthlyQuota,
        int usedThisPeriod,
        int purchasedRemaining,
        int available,
        Instant periodResetAt
) {
}
