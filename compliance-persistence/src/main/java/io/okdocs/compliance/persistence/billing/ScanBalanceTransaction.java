package io.okdocs.compliance.persistence.billing;

import io.okdocs.compliance.contracts.enums.BalanceTxnType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Append-only леджер движений баланса — источник правды. */
@Entity
@Table(name = "scan_balance_txns")
@Getter
@Setter
@NoArgsConstructor
public class ScanBalanceTransaction {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BalanceTxnType type;

    /** ± сканов (DEBIT отрицательный). */
    @Column(nullable = false)
    private int amount;

    /** available после операции (для аудита). */
    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    /** Для DEBIT/REFUND — какой скан. */
    @Column(name = "scan_id")
    private UUID scanId;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }
}
