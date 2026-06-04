package io.okdocs.compliance.persistence.billing;

import io.okdocs.compliance.contracts.enums.BalanceTxnType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ScanBalanceTransactionRepository extends JpaRepository<ScanBalanceTransaction, UUID> {

    Page<ScanBalanceTransaction> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** Идемпотентность refund: проверить, нет ли уже REFUND по этому скану. */
    boolean existsByScanIdAndType(UUID scanId, BalanceTxnType type);
}
