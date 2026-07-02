package io.okdocs.compliance.api.service.payment.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Покупатель в фискальном чеке (54-ФЗ): нужен email или телефон. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record YooKassaReceiptCustomer(
        String email,
        String phone
) {
}
