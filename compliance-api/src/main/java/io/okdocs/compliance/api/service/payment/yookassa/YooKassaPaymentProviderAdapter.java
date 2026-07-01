package io.okdocs.compliance.api.service.payment.yookassa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.api.config.YooKassaProperties;
import io.okdocs.compliance.api.service.payment.PaymentChargeContext;
import io.okdocs.compliance.api.service.payment.PaymentProviderAdapter;
import io.okdocs.compliance.api.service.payment.ProviderPayment;
import io.okdocs.compliance.api.service.payment.ProviderPaymentStatus;
import io.okdocs.compliance.api.service.payment.WebhookResult;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaAmount;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaConfirmation;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaCreatePaymentRequest;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaPaymentObject;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaPaymentResponse;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaReceipt;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaReceiptCustomer;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaReceiptItem;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaWebhookPayload;
import io.okdocs.compliance.contracts.enums.PaymentProvider;
import io.okdocs.compliance.contracts.enums.PaymentStatus;
import io.okdocs.compliance.persistence.billing.PaymentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Адаптер YooKassa (docs/PLAN-payments.md, Фаза 5). Переводит provider-нейтральные вызовы в YooKassa
 * API и обратно. Маппинг статусов: {@code pending/waiting_for_capture → PENDING},
 * {@code succeeded → SUCCEEDED}, {@code canceled → CANCELED}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YooKassaPaymentProviderAdapter implements PaymentProviderAdapter {

    /** Ключ metadata, по которому webhook находит локальную сессию (fallback к provider_payment_id). */
    public static final String META_PAYMENT_PUBLIC_ID = "paymentPublicId";

    private final YooKassaProperties properties;
    private final YooKassaClient client;
    private final ObjectMapper objectMapper;

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.YOOKASSA;
    }

    @Override
    public ProviderPayment createPayment(PaymentSession session, PaymentChargeContext context) {
        YooKassaAmount amount = new YooKassaAmount(
                session.getAmount().toPlainString(), session.getCurrency());

        String returnUrl = context.returnUrl() != null && !context.returnUrl().isBlank()
                ? context.returnUrl()
                : properties.returnUrl();
        YooKassaConfirmation confirmation = new YooKassaConfirmation("redirect", returnUrl, null);

        YooKassaReceipt receipt = buildReceipt(context.customerEmail(), context.description(), amount);

        Map<String, Object> metadata = Map.of(
                META_PAYMENT_PUBLIC_ID, session.getPublicId().toString(),
                "userId", session.getUserId().toString(),
                "productCode", session.getProductCode().name(),
                "credits", session.getCredits());

        YooKassaCreatePaymentRequest request = new YooKassaCreatePaymentRequest(
                amount, confirmation, true, context.description(), receipt, metadata,
                properties.testMode());

        YooKassaPaymentResponse response = client.createPayment(request, session.getIdempotenceKey());
        String confirmationUrl = response.confirmation() != null
                ? response.confirmation().confirmationUrl() : null;

        return new ProviderPayment(
                response.id(),
                null,
                confirmationUrl,
                response.expiresAt(),
                serialize(response));
    }

    @Override
    public ProviderPaymentStatus fetchStatus(PaymentSession session) {
        YooKassaPaymentObject payment = client.getPayment(session.getProviderPaymentId());
        return toStatus(payment);
    }

    @Override
    public WebhookResult parseWebhook(Object payload) {
        YooKassaWebhookPayload webhook = objectMapper.convertValue(payload, YooKassaWebhookPayload.class);
        YooKassaPaymentObject object = webhook.object();
        if (object == null) {
            return new WebhookResult(PaymentProvider.YOOKASSA, null, null, webhook.event());
        }
        UUID publicId = extractPublicId(object.metadata());
        return new WebhookResult(PaymentProvider.YOOKASSA, object.id(), publicId, webhook.event());
    }

    private ProviderPaymentStatus toStatus(YooKassaPaymentObject payment) {
        PaymentStatus status = mapStatus(payment.status());
        BigDecimal amount = payment.amount() != null ? new BigDecimal(payment.amount().value()) : null;
        String currency = payment.amount() != null ? payment.amount().currency() : null;
        Instant paidAt = status == PaymentStatus.SUCCEEDED ? payment.capturedAt() : null;
        Instant canceledAt = status == PaymentStatus.CANCELED ? Instant.now() : null;
        String failureReason = payment.cancellationDetails() != null
                ? payment.cancellationDetails().reason() : null;
        return new ProviderPaymentStatus(status, payment.id(), amount, currency, paidAt, canceledAt, failureReason);
    }

    /** Маппинг статуса YooKassa в нейтральный {@link PaymentStatus}. */
    private static PaymentStatus mapStatus(String yooKassaStatus) {
        if (yooKassaStatus == null) {
            return PaymentStatus.PENDING;
        }
        return switch (yooKassaStatus) {
            case "succeeded" -> PaymentStatus.SUCCEEDED;
            case "canceled" -> PaymentStatus.CANCELED;
            // pending / waiting_for_capture — ещё не финал.
            default -> PaymentStatus.PENDING;
        };
    }

    private YooKassaReceipt buildReceipt(String email, String description, YooKassaAmount amount) {
        YooKassaReceiptCustomer customer = new YooKassaReceiptCustomer(email, null);
        YooKassaReceiptItem item = new YooKassaReceiptItem(
                description, "1.00", amount, properties.vatCode(), "full_payment", "service");
        return new YooKassaReceipt(customer, List.of(item), properties.taxSystemCode());
    }

    private UUID extractPublicId(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object raw = metadata.get(META_PAYMENT_PUBLIC_ID);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            log.warn("YooKassa webhook metadata.{} не UUID: {}", META_PAYMENT_PUBLIC_ID, raw);
            return null;
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Не удалось сериализовать YooKassa payload для аудита", e);
            return null;
        }
    }
}
