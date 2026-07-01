package io.okdocs.compliance.api.service.payment;

import io.okdocs.compliance.contracts.enums.PaymentProvider;
import io.okdocs.compliance.persistence.billing.PaymentSession;

/**
 * Provider-нейтральный интерфейс адаптера платёжного провайдера (docs/PLAN-payments.md). Neutral
 * {@code PaymentService} оркеструет lifecycle (сессия/баланс/идемпотентность); адаптер знает только
 * как говорить с конкретным провайдером.
 * <p>
 * В этой итерации реализован только {@code YooKassaPaymentProviderAdapter}. {@link #fetchStatus} —
 * first-class метод (не опция), чтобы TON/reconciliation позже активировали баланс не только из webhook.
 */
public interface PaymentProviderAdapter {

    /** Какой провайдер обслуживает этот адаптер (для регистрации в роутере). */
    PaymentProvider provider();

    /**
     * Создать платёж у провайдера для уже сохранённой {@code session} (CREATED). Возвращает
     * provider-specific идентификаторы и confirmationUrl; сумма/валюта/idempotenceKey берутся из сессии.
     */
    ProviderPayment createPayment(PaymentSession session, PaymentChargeContext context);

    /**
     * Запросить актуальный статус платежа у провайдера. Источник правды для активации баланса —
     * webhook лишь триггерит этот вызов, а pull-only провайдеры (TON) опираются на него напрямую.
     */
    ProviderPaymentStatus fetchStatus(PaymentSession session);

    /** Распарсить webhook провайдера в нейтральный {@link WebhookResult} (без вердикта об успехе). */
    WebhookResult parseWebhook(Object payload);
}
