package io.okdocs.compliance.contracts.cabinet;

import java.time.Instant;

/**
 * Баланс сканов юзера. {@code available = monthlyQuota − usedThisPeriod + purchasedRemaining}
 * (в MVP {@code purchasedRemaining} всегда 0 — докупки нет).
 */
public record ScanBalanceDto(
        int monthlyQuota,
        int usedThisPeriod,
        int purchasedRemaining,
        int available,
        Instant periodResetAt
) {
}
