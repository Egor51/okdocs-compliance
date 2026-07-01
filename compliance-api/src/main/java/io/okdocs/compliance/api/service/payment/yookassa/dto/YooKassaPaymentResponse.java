package io.okdocs.compliance.api.service.payment.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/** Ответ YooKassa на создание платежа ({@code POST /payments}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YooKassaPaymentResponse(
        String id,
        String status,
        YooKassaAmount amount,
        String description,
        boolean paid,
        boolean refundable,
        YooKassaConfirmation confirmation,
        Map<String, Object> metadata,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("test") Boolean testMode
) {
}
