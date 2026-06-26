package io.okdocs.compliance.persistence.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface CheckoutSessionRepository extends JpaRepository<CheckoutSession, UUID> {

    /**
     * Пессимистичная блокировка сессии на время обработки webhook'а (F.14): сериализует параллельные
     * доставки одного платежа, пока первая не закоммитит consume. Вызывать внутри {@code @Transactional}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CheckoutSession> findWithLockById(UUID id);

    /** Идемпотентность по ключу провайдера: уже обработанный платёж находит свою сессию. */
    Optional<CheckoutSession> findByProviderAndProviderPaymentId(
            io.okdocs.compliance.contracts.enums.PaymentProvider provider, String providerPaymentId);
}
