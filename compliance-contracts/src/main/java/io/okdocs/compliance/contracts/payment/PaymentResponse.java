package io.okdocs.compliance.contracts.payment;

import io.okdocs.compliance.contracts.enums.ScanTier;

import java.math.BigDecimal;

/** Результат инициирования платежа. */
public record PaymentResponse(
        boolean activated,
        ScanTier tier,
        String confirmationUrl,
        BigDecimal finalAmount,
        Integer discountPct
) {
}
