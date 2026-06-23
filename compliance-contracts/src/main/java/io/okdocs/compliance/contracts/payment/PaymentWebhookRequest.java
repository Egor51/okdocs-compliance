package io.okdocs.compliance.contracts.payment;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Нормализованное тело webhook'а оплаты (F.4). В MVP-каркасе — провайдер-агностик; реальные
 * провайдеры (ЮKassa/Stripe) приходят со своими форматами и подписью (F.16), адаптер маппит их
 * в эту форму перед {@code handleWebhook}.
 *
 * @param checkoutId        id checkout-сессии (провайдер возвращает из metadata платежа)
 * @param provider          платёжный провайдер. Свободная строка ОСОЗНАННО до F.16: фиксированный
 *                          набор (YOOKASSA/STRIPE/CRYPTO) + enum + DB-CHECK вводятся вместе с
 *                          реальной интеграцией, когда состав провайдеров финализирован
 * @param providerPaymentId idempotency-ключ платежа от провайдера
 */
public record PaymentWebhookRequest(
        @NotNull UUID checkoutId,
        @NotNull String provider,
        @NotNull String providerPaymentId
) {
}
