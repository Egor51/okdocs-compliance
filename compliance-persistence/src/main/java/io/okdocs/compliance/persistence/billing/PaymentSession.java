package io.okdocs.compliance.persistence.billing;

import io.okdocs.compliance.contracts.enums.PaymentProvider;
import io.okdocs.compliance.contracts.enums.PaymentStatus;
import io.okdocs.compliance.contracts.enums.PricingPlanCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Платёжная сессия (Balance-first, docs/PLAN-payments.md): персистентный «заказ на кредиты»,
 * переживающий редирект к провайдеру и асинхронный webhook оплаты.
 * <p>
 * Успешная оплата НЕ запускает скан — только пополняет баланс через
 * {@code ScanBalanceService.purchaseFromPayment(...)}. Provider-specific поля
 * ({@code providerInvoiceId}, {@code metadataJson}, {@code providerPayloadJson}) заложены сразу, чтобы
 * Telegram/TON/Stripe добавлялись без миграции схемы.
 */
@Entity
@Table(name = "payment_sessions")
@Getter
@Setter
@NoArgsConstructor
public class PaymentSession {

    @Id
    private UUID id;

    /** Публичный id для фронта/возврата; внутренний {@link #id} наружу не отдаём. */
    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentProvider provider;

    /** id платежа у провайдера; заполняется после create (до этого {@code null}). */
    @Column(name = "provider_payment_id", length = 128)
    private String providerPaymentId;

    /** id инвойса (Telegram/TON) — задел; в YooKassa не используется. */
    @Column(name = "provider_invoice_id", length = 128)
    private String providerInvoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_code", nullable = false, length = 30)
    private PricingPlanCode productCode;

    @Column(nullable = false, length = 16)
    private String locale;

    /** Рынок (RU/INTL) — задел на provider-routing. */
    @Column(length = 16)
    private String market;

    /** Кредиты к зачислению при успехе (= included_reports продукта). */
    @Column(nullable = false)
    private int credits;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "confirmation_url", columnDefinition = "text")
    private String confirmationUrl;

    /** Наш Idempotence-Key для исходящего create у провайдера. */
    @Column(name = "idempotence_key", length = 128)
    private String idempotenceKey;

    @Column(name = "metadata_json", columnDefinition = "text")
    private String metadataJson;

    @Column(name = "provider_payload_json", columnDefinition = "text")
    private String providerPayloadJson;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
