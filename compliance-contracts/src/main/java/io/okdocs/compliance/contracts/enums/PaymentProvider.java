package io.okdocs.compliance.contracts.enums;

/**
 * Платёжный провайдер (Balance-first, docs/PLAN-payments.md). Метод оплаты выбирается роутером по
 * locale-подсказке, но enum общий — бэкенд знает весь roadmap-набор.
 * <ul>
 *   <li>{@code YOOKASSA} — карта РФ (RUB), реализован;</li>
 *   <li>{@code STRIPE} — международная карта (USD/EUR), позже;</li>
 *   <li>{@code TELEGRAM} — оплата через Telegram (invoice/deep-link), позже;</li>
 *   <li>{@code TON} — on-chain TON (pull/reconciliation), позже.</li>
 * </ul>
 * Реализован только {@code YOOKASSA}; остальные значения зарезервированы под адаптеры и выровнены с
 * CHECK-констрейнтом {@code payment_sessions.provider} (V027).
 */
public enum PaymentProvider {
    YOOKASSA,
    STRIPE,
    TELEGRAM,
    TON
}
