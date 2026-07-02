package io.okdocs.compliance.contracts.payment;

import io.okdocs.compliance.contracts.enums.PaymentProvider;
import io.okdocs.compliance.contracts.enums.PaymentStatus;
import io.okdocs.compliance.contracts.enums.PricingPlanCode;

import java.time.Instant;
import java.util.UUID;

/**
 * Статус платежа для поллинга фронтом (owner-only). При {@code PENDING} сервис может освежить статус
 * у провайдера и провести активацию баланса (готовит TON/reconciliation-путь, см. PLAN-payments.md).
 *
 * @param paymentPublicId   публичный id платёжной сессии
 * @param provider          провайдер
 * @param providerPaymentId id платежа у провайдера
 * @param status            текущий статус
 * @param productCode       продукт
 * @param credits           кредиты, зачисляемые при успехе
 * @param paidAt            момент успешной оплаты ({@code null} пока не оплачен)
 * @param canceledAt        момент отмены ({@code null} если не отменён)
 * @param failureReason     причина отказа провайдера ({@code null} если нет)
 */
public record PaymentStatusResponse(
        UUID paymentPublicId,
        PaymentProvider provider,
        String providerPaymentId,
        PaymentStatus status,
        PricingPlanCode productCode,
        int credits,
        Instant paidAt,
        Instant canceledAt,
        String failureReason
) {
}
