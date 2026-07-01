package io.okdocs.compliance.api.service.payment.yookassa.dto;

/** Сумма платежа YooKassa: строковое десятичное значение + валюта ISO-4217. */
public record YooKassaAmount(
        String value,
        String currency
) {
}
