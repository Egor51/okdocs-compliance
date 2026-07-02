package io.okdocs.compliance.api.service.payment;

import io.okdocs.compliance.contracts.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Удалённый статус платежа у провайдера (adapter.fetchStatus). Это first-class путь активации баланса,
 * а не только webhook: TON/reconciliation позже опираются на него (docs/PLAN-payments.md).
 * {@code amount}/{@code currency} нужны для сверки с локальной суммой перед пополнением.
 *
 * @param status            нормализованный статус платежа
 * @param providerPaymentId id платежа у провайдера (для сверки)
 * @param amount            сумма по данным провайдера; {@code null} если провайдер не вернул
 * @param currency          валюта по данным провайдера; {@code null} если не вернул
 * @param paidAt            момент успешной оплаты; {@code null} если ещё не оплачен
 * @param canceledAt        момент отмены; {@code null} если не отменён
 * @param failureReason     причина отказа провайдера; {@code null} если нет
 */
public record ProviderPaymentStatus(
        PaymentStatus status,
        String providerPaymentId,
        BigDecimal amount,
        String currency,
        Instant paidAt,
        Instant canceledAt,
        String failureReason
) {
}
