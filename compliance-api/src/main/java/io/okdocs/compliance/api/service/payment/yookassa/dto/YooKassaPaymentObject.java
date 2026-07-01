package io.okdocs.compliance.api.service.payment.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/** Объект платежа YooKassa (ответ {@code GET /payments/{id}} и тело внутри webhook'а). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YooKassaPaymentObject(
        String id,
        String status,
        YooKassaAmount amount,
        String description,
        boolean paid,
        @JsonProperty("captured_at") Instant capturedAt,
        @JsonProperty("expires_at") Instant expiresAt,
        YooKassaConfirmation confirmation,
        Map<String, Object> metadata,
        @JsonProperty("cancellation_details") YooKassaCancellationDetails cancellationDetails
) {
}
