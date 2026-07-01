package io.okdocs.compliance.api.service.payment;

import io.okdocs.compliance.contracts.enums.PaymentProvider;

import java.util.UUID;

/**
 * Результат парсинга webhook'а провайдера: чего достаточно, чтобы найти локальную сессию. Сам факт
 * успеха НЕ берём из webhook'а — после нахождения сессии перепроверяем статус через
 * {@code adapter.fetchStatus(...)} (webhook лишь триггер, не источник правды; docs/PLAN-payments.md).
 *
 * @param provider          провайдер
 * @param providerPaymentId id платежа у провайдера (основной ключ поиска)
 * @param paymentPublicId   наш публичный id из metadata платежа (fallback-ключ); {@code null} если нет
 * @param rawEvent          тип события провайдера (для логов/аудита)
 */
public record WebhookResult(
        PaymentProvider provider,
        String providerPaymentId,
        UUID paymentPublicId,
        String rawEvent
) {
}
