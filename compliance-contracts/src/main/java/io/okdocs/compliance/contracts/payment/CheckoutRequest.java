package io.okdocs.compliance.contracts.payment;

import jakarta.validation.constraints.NotBlank;

/**
 * Запуск оплаты premium-скана из кабинета (F.4 §F12). {@code userId} НЕ принимается с фронта —
 * берётся из JWT. {@code siteUrl}/{@code jurisdiction} приходят как недоверенный prefill
 * (из {@code /register?url=&jur=}) и валидируются бэкендом при создании сессии и запуске скана.
 *
 * @param siteUrl      сайт для premium-проверки
 * @param jurisdiction по какому закону проверять
 * @param promoCode    опциональный промокод
 * @param locale       язык отчёта (ru/en/de/fr/es); опционально, сервер ставит дефолт; ось ≠ jurisdiction
 */
public record CheckoutRequest(
        @NotBlank String siteUrl,
        @NotBlank String jurisdiction,
        String promoCode,
        String locale
) {
}
