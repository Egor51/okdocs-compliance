package io.okdocs.compliance.contracts.enums;

/**
 * Состояние платёжной сессии (Balance-first, см. docs/PLAN-payments.md). Оплата НЕ запускает скан —
 * успешный платёж лишь пополняет баланс кредитов ({@code purchasedRemaining}), а premium-скан юзер
 * запускает отдельно через {@code /api/cabinet/scans}.
 * <ul>
 *   <li>{@code CREATED} — сессия создана локально, провайдер ещё не вызван;</li>
 *   <li>{@code CREATE_FAILED} — вызов create у провайдера завершился НЕОПРЕДЕЛЁННО (timeout/разрыв):
 *       платёж мог быть создан, но ответ не дошёл. Non-terminal — webhook/recovery может довести
 *       платёж (записать providerPaymentId под lock и активировать);</li>
 *   <li>{@code PENDING} — платёж создан у провайдера, ждём оплаты/webhook'а (не терминал);</li>
 *   <li>{@code SUCCEEDED} — оплата подтверждена, баланс пополнен (терминал);</li>
 *   <li>{@code CANCELED} — отменён пользователем/провайдером (терминал);</li>
 *   <li>{@code FAILED} — провайдер сообщил об ошибке оплаты (терминал).</li>
 * </ul>
 */
public enum PaymentStatus {
    CREATED,
    CREATE_FAILED,
    PENDING,
    SUCCEEDED,
    CANCELED,
    FAILED;

    /** Терминальные статусы: повторный webhook по ним — no-op (идемпотентность). */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELED || this == FAILED;
    }
}
