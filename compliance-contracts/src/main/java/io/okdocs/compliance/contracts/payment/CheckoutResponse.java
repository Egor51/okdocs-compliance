package io.okdocs.compliance.contracts.payment;

import java.util.UUID;

/**
 * Ответ на создание checkout-сессии (F.4 §F12). Фронт редиректит на {@code confirmationUrl}
 * (страница провайдера). Активация PRO/скана — НЕ здесь, а по webhook'у после оплаты.
 *
 * @param checkoutId      id созданной сессии (для поллинга статуса)
 * @param confirmationUrl URL страницы оплаты у провайдера
 */
public record CheckoutResponse(
        UUID checkoutId,
        String confirmationUrl
) {
}
