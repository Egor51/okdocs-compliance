package io.okdocs.compliance.api.service.payment.yookassa;

import io.okdocs.compliance.api.config.YooKassaProperties;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaCreatePaymentRequest;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaPaymentObject;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaPaymentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP-клиент YooKassa API (один магазин). Использует {@link RestClient} (spring-web, без webflux).
 * Credentials берутся из {@link YooKassaProperties} (env/config) — в коде их нет.
 */
@Slf4j
@Component
public class YooKassaClient {

    private final YooKassaProperties properties;
    private final RestClient restClient;

    public YooKassaClient(YooKassaProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.apiBaseUrl())
                .defaultHeaders(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    if (properties.isConfigured()) {
                        headers.setBasicAuth(properties.shopId(), properties.secretKey());
                    }
                })
                .build();
    }

    /**
     * Fail-fast до внешнего вызова: без shopId/secretKey BasicAuth не ставится и YooKassa вернула бы
     * невнятную 401 — лучше явная ошибка конфигурации.
     */
    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new YooKassaException(
                    "YooKassa не сконфигурирована: нет yookassa.shop-id / yookassa.secret-key", null);
        }
    }

    /** Создать платёж. {@code idempotenceKey} (наш) защищает от дублей на стороне YooKassa. */
    public YooKassaPaymentResponse createPayment(YooKassaCreatePaymentRequest request, String idempotenceKey) {
        requireConfigured();
        try {
            return restClient.post()
                    .uri("/payments")
                    .header("Idempotence-Key", idempotenceKey)
                    .body(request)
                    .retrieve()
                    .body(YooKassaPaymentResponse.class);
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            log.error("YooKassa create payment error: status={}, body={}", e.getStatusCode(), body, e);
            throw new YooKassaException("Ошибка при создании платежа в YooKassa: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("Unexpected error while creating YooKassa payment", e);
            throw new YooKassaException("Не удалось создать платёж в YooKassa", e);
        }
    }

    /** Получить актуальное состояние платежа (для сверки перед пополнением баланса). */
    public YooKassaPaymentObject getPayment(String paymentId) {
        requireConfigured();
        try {
            return restClient.get()
                    .uri("/payments/{paymentId}", paymentId)
                    .retrieve()
                    .body(YooKassaPaymentObject.class);
        } catch (RestClientResponseException e) {
            log.error("YooKassa get payment error: status={}, body={}", e.getStatusCode(),
                    e.getResponseBodyAsString(), e);
            throw new YooKassaException("Ошибка при запросе платежа в YooKassa: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("Unexpected error while fetching YooKassa payment", e);
            throw new YooKassaException("Не удалось получить платёж в YooKassa", e);
        }
    }

    /** Сбой обращения к YooKassa API. */
    public static class YooKassaException extends RuntimeException {
        public YooKassaException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
