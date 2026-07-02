package io.okdocs.compliance.api.service.payment.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Подтверждение оплаты YooKassa: на запрос отправляем {@code type+return_url}, в ответе приходит {@code confirmation_url}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record YooKassaConfirmation(
        String type,
        @JsonProperty("return_url") String returnUrl,
        @JsonProperty("confirmation_url") String confirmationUrl
) {
}
