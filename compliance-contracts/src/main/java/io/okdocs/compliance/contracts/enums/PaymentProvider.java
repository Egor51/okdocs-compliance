package io.okdocs.compliance.contracts.enums;

/**
 * Платёжный провайдер (§4a/F.4). Метод оплаты выбирается на checkout по locale-подсказке, но enum
 * общий — бэкенд принимает любой из известных.
 * <ul>
 *   <li>{@code YOOKASSA} — карта РФ (RUB);</li>
 *   <li>{@code STRIPE} — международная карта (EUR/USD);</li>
 *   <li>{@code CRYPTO} — крипто-провайдер (USDT/BTC).</li>
 * </ul>
 * Реальная интеграция (confirmationUrl/подпись/курсы) — F.16; здесь фиксируется допустимый набор.
 */
public enum PaymentProvider {
    YOOKASSA,
    STRIPE,
    CRYPTO
}
