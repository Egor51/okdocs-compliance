package io.okdocs.compliance.api.service.payment;

import java.time.Instant;

/**
 * Результат создания платежа у провайдера (adapter → neutral service). Нейтрально к провайдеру:
 * YooKassa отдаёт {@code confirmationUrl}, Telegram/TON позже — {@code providerInvoiceId} и т.п.
 *
 * @param providerPaymentId   id платежа у провайдера
 * @param providerInvoiceId   id инвойса (Telegram/TON); {@code null} для YooKassa
 * @param confirmationUrl     URL страницы оплаты (redirect); может быть {@code null} для invoice-флоу
 * @param expiresAt           когда истекает окно оплаты; {@code null} если провайдер не сообщает
 * @param providerPayloadJson сырой ответ провайдера для аудита (сохраняется в payment_sessions)
 */
public record ProviderPayment(
        String providerPaymentId,
        String providerInvoiceId,
        String confirmationUrl,
        Instant expiresAt,
        String providerPayloadJson
) {
}
