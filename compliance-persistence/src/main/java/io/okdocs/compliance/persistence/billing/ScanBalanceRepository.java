package io.okdocs.compliance.persistence.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface ScanBalanceRepository extends JpaRepository<ScanBalance, Long> {

    Optional<ScanBalance> findByUserId(Long userId);

    /**
     * Пессимистичная блокировка строки баланса на время списания — альтернатива/дополнение
     * к {@code @Version} при высокой конкуренции по одному юзеру. Вызывать внутри {@code @Transactional}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ScanBalance> findWithLockByUserId(Long userId);
}
