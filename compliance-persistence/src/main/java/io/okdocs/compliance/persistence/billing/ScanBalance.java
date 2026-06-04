package io.okdocs.compliance.persistence.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Баланс сканов юзера (один на юзера, PK = userId). Денормализованный «текущий» снимок;
 * неизменяемая история — в {@link ScanBalanceTransaction}.
 * <p>
 * {@code @Version} обязателен: два параллельных startScan не должны продать один последний скан.
 */
@Entity
@Table(name = "scan_balances")
@Getter
@Setter
@NoArgsConstructor
public class ScanBalance {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "monthly_quota", nullable = false)
    private int monthlyQuota;

    @Column(name = "used_this_period", nullable = false)
    private int usedThisPeriod;

    /** Докупленные сканы (не сгорают). В MVP всегда 0 — докупки нет. */
    @Column(name = "purchased_remaining", nullable = false)
    private int purchasedRemaining;

    @Column(name = "period_reset_at", nullable = false)
    private Instant periodResetAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    /** Доступно к списанию: остаток месячной квоты + докупленное. */
    public int available() {
        return (monthlyQuota - usedThisPeriod) + purchasedRemaining;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
