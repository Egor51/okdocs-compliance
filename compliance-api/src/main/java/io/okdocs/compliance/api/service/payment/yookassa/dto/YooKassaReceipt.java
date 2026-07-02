package io.okdocs.compliance.api.service.payment.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Фискальный чек YooKassa (54-ФЗ): покупатель + позиции. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record YooKassaReceipt(
        YooKassaReceiptCustomer customer,
        List<YooKassaReceiptItem> items,
        @JsonProperty("tax_system_code") Integer taxSystemCode
) {
}
