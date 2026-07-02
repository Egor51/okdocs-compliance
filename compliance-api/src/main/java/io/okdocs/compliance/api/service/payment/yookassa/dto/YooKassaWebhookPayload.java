package io.okdocs.compliance.api.service.payment.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Webhook-уведомление YooKassa: тип события + объект платежа. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YooKassaWebhookPayload(
        String event,
        YooKassaPaymentObject object
) {
}
