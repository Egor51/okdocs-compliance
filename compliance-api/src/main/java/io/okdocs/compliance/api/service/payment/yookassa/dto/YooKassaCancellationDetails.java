package io.okdocs.compliance.api.service.payment.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Детали отмены платежа YooKassa (кто и почему отменил). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YooKassaCancellationDetails(
        String party,
        String reason
) {
}
