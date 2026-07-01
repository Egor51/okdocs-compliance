package io.okdocs.compliance.persistence.billing;

import io.okdocs.compliance.contracts.enums.PaymentProvider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface PaymentSessionRepository extends JpaRepository<PaymentSession, UUID> {

    Optional<PaymentSession> findByPublicId(UUID publicId);

    /** Идемпотентность webhook по ключу провайдера ДО блокировки (дешёвый terminal-guard). */
    Optional<PaymentSession> findByProviderAndProviderPaymentId(PaymentProvider provider,
                                                                String providerPaymentId);

    /**
     * Пессимистичная блокировка сессии на время обработки webhook'а: сериализует параллельные
     * доставки одного платежа, пока первая не закоммитит активацию. Вызывать в {@code @Transactional}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentSession> findWithLockById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentSession> findWithLockByPublicId(UUID publicId);
}
