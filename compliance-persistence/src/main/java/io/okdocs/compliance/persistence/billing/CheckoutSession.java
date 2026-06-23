package io.okdocs.compliance.persistence.billing;

import io.okdocs.compliance.contracts.enums.CheckoutStatus;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Checkout-сессия (F.4): персистентный «заказ» premium-скана, переживающий редирект к провайдеру
 * и асинхронный webhook оплаты. Источник идемпотентности и восстановления при сбое старта.
 */
@Entity
@Table(name = "checkout_sessions")
@Getter
@Setter
@NoArgsConstructor
public class CheckoutSession {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "site_url", nullable = false, length = 2048)
    private String siteUrl;

    @Column(name = "site_domain", nullable = false)
    private String siteDomain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScanJurisdiction jurisdiction;

    @Column(name = "promo_code", length = 64)
    private String promoCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CheckoutStatus status;

    @Column(length = 30)
    private String provider;

    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Column(name = "premium_scan_id")
    private UUID premiumScanId;

    private BigDecimal amount;

    @Column(length = 8)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
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
