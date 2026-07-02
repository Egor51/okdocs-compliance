package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.security.CurrentPrincipal;
import io.okdocs.compliance.api.service.payment.PaymentService;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaWebhookPayload;
import io.okdocs.compliance.contracts.payment.CreatePaymentRequest;
import io.okdocs.compliance.contracts.payment.CreatePaymentResponse;
import io.okdocs.compliance.contracts.payment.PaymentStatusResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * API платежей (Balance-first, docs/PLAN-payments.md). Платёж покупает кредиты баланса; premium-скан
 * запускается отдельно через {@code /api/cabinet/scans}.
 * <ul>
 *   <li>{@code POST /api/payments/balance} — USER создаёт платёж-пополнение;</li>
 *   <li>{@code GET /api/payments/{publicId}/status} — owner поллит статус;</li>
 *   <li>{@code POST /api/payments/webhooks/yookassa} — публичный webhook YooKassa.</li>
 * </ul>
 * Список продуктов отдаёт существующий {@code GET /api/pricing/plans}.
 */
@Slf4j
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final String SECRET_HEADER = "X-Webhook-Secret";

    private final PaymentService paymentService;
    private final ComplianceApiProperties properties;

    /**
     * Создать платёж. Один endpoint для всех продуктов: top-up баланса (ONE_REPORT) и тариф
     * (PRO/BUSINESS) — тип определяется {@code productCode}. userId из JWT (только USER).
     */
    @PostMapping
    public CreatePaymentResponse createPayment(@Valid @RequestBody CreatePaymentRequest request) {
        Long userId = CurrentPrincipal.require().userId();
        return paymentService.createPayment(userId, request);
    }

    /**
     * @deprecated теперь создаёт не только top-up, но и тариф — используйте {@code POST /api/payments}.
     * Алиас оставлен на 1–2 релиза для совместимости фронта.
     */
    @Deprecated
    @PostMapping("/balance")
    public CreatePaymentResponse createBalancePayment(@Valid @RequestBody CreatePaymentRequest request) {
        Long userId = CurrentPrincipal.require().userId();
        return paymentService.createPayment(userId, request);
    }

    /** Статус платежа (owner-only). */
    @GetMapping("/{publicId}/status")
    public PaymentStatusResponse status(@PathVariable UUID publicId) {
        Long userId = CurrentPrincipal.require().userId();
        return paymentService.getPaymentStatus(userId, publicId);
    }

    /**
     * Webhook YooKassa (публичный — у провайдера нет JWT). Защита двухуровневая: (1) fail-closed
     * shared-secret из header {@code X-Webhook-Secret}; (2) сервис перепроверяет факт оплаты у
     * провайдера ({@code fetchStatus}) перед пополнением. Обработка идемпотентна; всегда отвечаем
     * {@code 200} на принятый запрос, чтобы провайдер не зацикливал доставку.
     */
    @PostMapping("/webhooks/yookassa")
    public ResponseEntity<Void> yooKassaWebhook(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @RequestBody YooKassaWebhookPayload payload) {
        if (!secretMatches(secret)) {
            log.warn("YooKassa webhook отклонён: неверный/отсутствующий {}", SECRET_HEADER);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        paymentService.handleYooKassaWebhook(payload);
        return ResponseEntity.ok().build();
    }

    /** Constant-time сравнение секрета; fail-closed при незаданном секрете. */
    private boolean secretMatches(String provided) {
        String expected = properties.payment().webhookSecret();
        if (expected == null || expected.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
