package io.okdocs.compliance.api.service.payment;

import io.okdocs.compliance.contracts.enums.PaymentStatus;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.persistence.billing.PaymentSession;
import io.okdocs.compliance.persistence.billing.PaymentSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Транзакционные записи платёжной сессии вокруг внешнего вызова провайдера (docs/PLAN-payments.md,
 * P1-фикс «orphan payment»). Вынесено в отдельный бин, чтобы каждая фаза была НЕЗАВИСИМОЙ транзакцией
 * с реальным commit'ом (через Spring-proxy), а внешний HTTP-вызов YooKassa шёл МЕЖДУ ними, вне tx.
 * <p>
 * Flow: {@link #createPending} (CREATED, commit) → provider.createPayment (вне tx) →
 * {@link #markPending} (PENDING, commit) либо {@link #markCreateFailed} при неопределённой ошибке create.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSessionWriter {

    private final PaymentSessionRepository sessionRepository;

    /** Транзакция 1: сохранить сессию в CREATED и закоммитить (ДО вызова провайдера). */
    @Transactional
    public PaymentSession createPending(PaymentSession session) {
        return sessionRepository.save(session); // @PrePersist проставит id/publicId/таймстемпы
    }

    /** Транзакция 2 (успех провайдера): записать provider-поля и перевести в PENDING. */
    @Transactional
    public PaymentSession markPending(UUID sessionId, ProviderPayment providerPayment) {
        PaymentSession session = load(sessionId);
        session.setProviderPaymentId(providerPayment.providerPaymentId());
        session.setProviderInvoiceId(providerPayment.providerInvoiceId());
        session.setConfirmationUrl(providerPayment.confirmationUrl());
        session.setExpiresAt(providerPayment.expiresAt());
        session.setProviderPayloadJson(providerPayment.providerPayloadJson());
        session.setStatus(PaymentStatus.PENDING);
        return sessionRepository.save(session);
    }

    /**
     * Транзакция 2 (неопределённый результат create): пометить сессию <b>non-terminal</b>
     * {@link PaymentStatus#CREATE_FAILED} с диагностикой. НЕ ставим terminal FAILED: при timeout/разрыве
     * платёж мог реально создаться у провайдера, и webhook/recovery должен иметь шанс довести его —
     * terminal-guard иначе проигнорировал бы webhook.
     */
    @Transactional
    public void markCreateFailed(UUID sessionId, String reason) {
        PaymentSession session = load(sessionId);
        session.setStatus(PaymentStatus.CREATE_FAILED);
        session.setFailureReason(reason);
        sessionRepository.save(session);
        log.warn("Платёж {} помечен CREATE_FAILED (неопределённо): {}", session.getPublicId(), reason);
    }

    /**
     * Recovery: записать providerPaymentId под блокировкой строки, если его ещё нет (webhook пришёл по
     * paymentPublicId для сессии, чей create вернул неопределённый результат). Возвращает {@code true},
     * если id привязан/уже совпадает — после этого активацию можно продолжать.
     */
    @Transactional
    public boolean attachProviderPaymentId(UUID sessionId, String providerPaymentId) {
        if (providerPaymentId == null) {
            return false;
        }
        PaymentSession session = sessionRepository.findWithLockById(sessionId)
                .orElseThrow(() -> new ComplianceValidationException("Платёж не найден: " + sessionId));
        if (session.getStatus().isTerminal()) {
            return false;
        }
        String existing = session.getProviderPaymentId();
        if (existing != null) {
            // Уже привязан — совпадение ок, расхождение подозрительно (не перетираем).
            return existing.equals(providerPaymentId);
        }
        session.setProviderPaymentId(providerPaymentId);
        // → PENDING: webhook доказывает, что create у провайдера прошёл. Покрываем обе гонки:
        // CREATE_FAILED (create вернул неопределённость) и CREATED (markPending ещё не успел/упал —
        // webhook опередил). В обоих случаях статус «до provider id» уже неверен.
        if (session.getStatus() == PaymentStatus.CREATED
                || session.getStatus() == PaymentStatus.CREATE_FAILED) {
            session.setStatus(PaymentStatus.PENDING);
            session.setFailureReason(null);
        }
        sessionRepository.save(session);
        log.info("Платёж {} recovery: привязан providerPaymentId={}", session.getPublicId(), providerPaymentId);
        return true;
    }

    private PaymentSession load(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ComplianceValidationException("Платёж не найден: " + sessionId));
    }
}
