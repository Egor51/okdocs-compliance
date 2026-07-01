package io.okdocs.compliance.api.service.payment.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** Тело запроса на создание платежа YooKassa ({@code POST /payments}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record YooKassaCreatePaymentRequest(
        YooKassaAmount amount,
        YooKassaConfirmation confirmation,
        boolean capture,
        String description,
        YooKassaReceipt receipt,
        Map<String, Object> metadata,
        @JsonProperty("test") Boolean testMode
) {
}
