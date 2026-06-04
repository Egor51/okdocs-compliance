package io.okdocs.compliance.contracts.scan;

import jakarta.validation.constraints.Email;

/** Инициирование оплаты PREMIUM. {@code promoCode} опционален. */
public record PaymentRequest(
        @Email String email,
        String promoCode
) {
}
