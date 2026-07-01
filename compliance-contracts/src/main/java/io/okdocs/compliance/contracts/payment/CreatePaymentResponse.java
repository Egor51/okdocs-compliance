package io.okdocs.compliance.contracts.payment;

import io.okdocs.compliance.contracts.enums.PaymentProvider;
import io.okdocs.compliance.contracts.enums.PaymentStatus;
import io.okdocs.compliance.contracts.enums.PricingPlanCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Ответ на создание платежа. Фронт редиректит юзера на {@code confirmationUrl} (страница провайдера);
 * статус потом поллится по {@code paymentPublicId} через {@code GET /api/payments/{publicId}/status}.
 *
 * @param paymentPublicId   публичный id платёжной сессии (для поллинга/возврата)
 * @param provider          выбранный провайдер
 * @param providerPaymentId id платежа у провайдера
 * @param confirmationUrl   URL страницы оплаты у провайдера (redirect)
 * @param status            статус платежа ({@code PENDING} сразу после создания у провайдера)
 * @param productCode       купленный продукт
 * @param credits           сколько кредитов зачислится на баланс при успешной оплате
 * @param amount            сумма к оплате
 * @param currency          валюта (ISO-4217)
 * @param expiresAt         когда у провайдера истекает окно оплаты (может быть {@code null})
 */
public record CreatePaymentResponse(
        UUID paymentPublicId,
        PaymentProvider provider,
        String providerPaymentId,
        String confirmationUrl,
        PaymentStatus status,
        PricingPlanCode productCode,
        int credits,
        BigDecimal amount,
        String currency,
        Instant expiresAt
) {
}
