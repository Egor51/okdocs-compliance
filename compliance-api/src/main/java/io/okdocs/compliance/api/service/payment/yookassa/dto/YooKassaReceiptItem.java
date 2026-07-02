package io.okdocs.compliance.api.service.payment.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Позиция фискального чека YooKassa (54-ФЗ). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record YooKassaReceiptItem(
        String description,
        String quantity,
        YooKassaAmount amount,
        @JsonProperty("vat_code") int vatCode,
        @JsonProperty("payment_mode") String paymentMode,
        @JsonProperty("payment_subject") String paymentSubject
) {
}
