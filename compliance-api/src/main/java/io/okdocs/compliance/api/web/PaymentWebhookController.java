package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.service.CheckoutService;
import io.okdocs.compliance.contracts.payment.PaymentWebhookRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Webhook оплаты от провайдера (F.4). Публичный (у провайдера нет JWT) — путь permit'нут в
 * {@code SecurityConfig}.
 * <p>
 * <b>Аутентификация запроса (P1):</b> до обработки сверяем shared-secret из header
 * {@code X-Webhook-Secret} с {@code compliance.payment.webhook-secret} — иначе кто угодно подделкой
 * JSON получил бы бесплатный premium (создал checkout → сам дёрнул webhook). <b>Fail-closed:</b>
 * если секрет не сконфигурирован, webhook отвергает все запросы. Штатная проверка подписи конкретного
 * провайдера (по сырому body+headers) — F.16, отдельными adapter-эндпоинтами.
 * <p>
 * Обработка идемпотентна; всегда отвечаем {@code 200} на принятый запрос, чтобы провайдер не
 * зацикливал доставку.
 */
@Slf4j
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private static final String SECRET_HEADER = "X-Webhook-Secret";

    private final CheckoutService checkoutService;
    private final ComplianceApiProperties properties;

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestHeader(value = SECRET_HEADER, required = false) String secret,
                                        @Valid @RequestBody PaymentWebhookRequest request) {
        if (!secretMatches(secret)) {
            log.warn("Webhook отклонён: неверный/отсутствующий {} (checkoutId={})", SECRET_HEADER, request.checkoutId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        checkoutService.handleWebhook(request.checkoutId(), request.provider(), request.providerPaymentId());
        return ResponseEntity.ok().build();
    }

    /** Constant-time сравнение; fail-closed при незаданном секрете. */
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
