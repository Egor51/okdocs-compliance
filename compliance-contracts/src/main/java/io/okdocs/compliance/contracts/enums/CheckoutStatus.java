package io.okdocs.compliance.contracts.enums;

/**
 * Состояние checkout-сессии (F.4). Связывает «юзер начал платить» с асинхронным webhook'ом оплаты
 * и обеспечивает идемпотентность + восстановление при сбое старта скана.
 * <ul>
 *   <li>{@code CREATED} — сессия создана, оплата ещё не подтверждена webhook'ом;</li>
 *   <li>{@code PAID_CONSUMED} — оплата прошла, баланс пополнен и premium-скан запущен (терминал);</li>
 *   <li>{@code PAID_NOT_CONSUMED} — оплата прошла, но скан ещё не запущен (промежуточное/для retry);</li>
 *   <li>{@code PAID_FAILED_TO_START} — оплата прошла, запуск скана упал; purchase откатан,
 *       платёж ждёт retry-job (деньги не потеряны).</li>
 * </ul>
 */
public enum CheckoutStatus {
    CREATED,
    PAID_CONSUMED,
    PAID_NOT_CONSUMED,
    PAID_FAILED_TO_START
}
