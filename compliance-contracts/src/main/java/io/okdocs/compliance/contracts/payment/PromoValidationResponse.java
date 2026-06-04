package io.okdocs.compliance.contracts.payment;

import java.math.BigDecimal;

/** Результат проверки промокода. {@code freeAccess} — промокод даёт PREMIUM бесплатно. */
public record PromoValidationResponse(
        boolean valid,
        Integer discountPct,
        BigDecimal finalAmount,
        boolean freeAccess,
        String errorMessage
) {
}
