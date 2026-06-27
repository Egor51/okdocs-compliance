package io.okdocs.compliance.contracts.payment;

import io.okdocs.compliance.contracts.enums.PaymentProvider;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Нормализованное тело webhook'а оплаты (F.4). В MVP-каркасе — провайдер-агностик; реальные
 * провайдеры (ЮKassa/Stripe) приходят со своими форматами и подписью (F.16), адаптер маппит их
 * в эту форму перед {@code handleWebhook}.
 *
 * @param checkoutId        id checkout-сессии (провайдер возвращает из metadata платежа)
 * @param provider          платёжный провайдер из фиксированного набора {@link PaymentProvider}
 *                          (неизвестное значение → 400 на десериализации enum)
 * @param providerPaymentId idempotency-ключ платежа от провайдера
 */
public record PaymentWebhookRequest(
        @NotNull UUID checkoutId,
        @NotNull PaymentProvider provider,
        @NotNull String providerPaymentId
) {
}
