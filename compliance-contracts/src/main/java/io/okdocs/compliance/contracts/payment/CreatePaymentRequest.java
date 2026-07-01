package io.okdocs.compliance.contracts.payment;

import io.okdocs.compliance.contracts.enums.PaymentProvider;
import io.okdocs.compliance.contracts.enums.PricingPlanCode;
import jakarta.validation.constraints.NotNull;

/**
 * Запрос на создание платежа-пополнения баланса (Balance-first, docs/PLAN-payments.md).
 * {@code userId} НЕ принимается с фронта — берётся из JWT.
 *
 * <p>В текущей итерации покупаем только {@code ONE_REPORT} (1 кредит); {@code PRO}/{@code BUSINESS}
 * — подписки, не top-up, и отвергаются сервисом.
 *
 * @param productCode продукт из pricing-каталога ({@code ONE_REPORT} в этой итерации)
 * @param provider    желаемый провайдер; {@code null} → router выбирает дефолт по locale (ru → YOOKASSA)
 * @param locale      язык чека/цены (ru/en); опционально, сервер ставит дефолт
 * @param returnUrl   опциональный override URL возврата после оплаты у провайдера
 */
public record CreatePaymentRequest(
        @NotNull PricingPlanCode productCode,
        PaymentProvider provider,
        String locale,
        String returnUrl
) {
}
