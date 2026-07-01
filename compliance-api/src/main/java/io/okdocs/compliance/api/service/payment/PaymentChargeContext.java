package io.okdocs.compliance.api.service.payment;

/**
 * Данные для создания платежа у провайдера, выходящие за рамки {@code PaymentSession} (которая уже
 * несёт amount/currency/credits/idempotenceKey/metadata). Нейтрально к провайдеру; YooKassa берёт
 * отсюда описание и email для фискального чека (54-ФЗ).
 *
 * @param description    человекочитаемое описание платежа (для чека и страницы провайдера)
 * @param customerEmail  email плательщика для чека; для YooKassa обязателен
 * @param returnUrl      куда вернуть юзера после оплаты; {@code null} → дефолт магазина
 */
public record PaymentChargeContext(
        String description,
        String customerEmail,
        String returnUrl
) {
}
