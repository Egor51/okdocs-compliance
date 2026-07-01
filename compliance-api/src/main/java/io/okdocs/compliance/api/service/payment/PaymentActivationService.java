package io.okdocs.compliance.api.service.payment;

import io.okdocs.compliance.api.service.ScanBalanceService;
import io.okdocs.compliance.contracts.enums.PaymentStatus;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.persistence.billing.PaymentSession;
import io.okdocs.compliance.persistence.billing.PaymentSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Активация платежа: перепроверка статуса у провайдера + пополнение баланса под блокировкой строки
 * (docs/PLAN-payments.md, P1-фикс). Вынесена в ОТДЕЛЬНЫЙ бин намеренно: {@code @Transactional} на
 * {@link #activate} срабатывает только через Spring-proxy, а вызов из того же {@code PaymentService}
 * (self-invocation) прокси миновал бы — тогда {@code findWithLockById} и атомарность
 * «статус сессии + purchaseFromPayment» были бы ненадёжны.
 * <p>
 * Идемпотентность: terminal-guard под lock + unique-индекс {@code PURCHASE(payment_id)} в леджере.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentActivationService {

    private final PaymentSessionRepository sessionRepository;
    private final ScanBalanceService balanceService;
    private final PaidPlanService paidPlanService;

    /**
     * Атомарно активировать платёж под пессимистичной блокировкой строки. Сверяет статус/сумму у
     * провайдера и при успехе пополняет баланс.
     * <p>
     * {@link Propagation#REQUIRES_NEW}: активация ВСЕГДА своя write-транзакция, независимо от
     * вызывающего пути. Это критично для pull-activation из {@code getPaymentStatus} (read-only tx):
     * без нового контекста запись SUCCEEDED + пополнение баланса присоединились бы к read-only tx.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void activate(UUID sessionId, PaymentProviderAdapter adapter) {
        PaymentSession session = sessionRepository.findWithLockById(sessionId)
                .orElseThrow(() -> new ComplianceValidationException("Платёж не найден: " + sessionId));
        if (session.getStatus().isTerminal()) {
            // Конкурентный webhook уже завершил активацию, пока мы ждали lock — идемпотентность.
            return;
        }
        if (session.getProviderPaymentId() == null) {
            // Нет id у провайдера (create не дозавершён, recovery не привязал) — спрашивать нечего.
            log.info("Платёж {} без providerPaymentId — активация пропущена", session.getPublicId());
            return;
        }

        ProviderPaymentStatus remote = adapter.fetchStatus(session);
        switch (remote.status()) {
            case SUCCEEDED -> {
                verifyMatchesProvider(session, remote);
                session.setStatus(PaymentStatus.SUCCEEDED);
                session.setPaidAt(remote.paidAt() != null ? remote.paidAt() : Instant.now());
                sessionRepository.save(session);
                // Доменная активация — в той же транзакции, идемпотентна по payment.id. Тип зависит от
                // продукта: PRO/BUSINESS активируют тариф аккаунта, ONE_REPORT пополняет баланс.
                if (PaidPlanService.isPaidPlanProduct(session.getProductCode())) {
                    paidPlanService.activateFromPayment(session.getUserId(), session.getProductCode(), session.getId());
                    log.info("Платёж {} SUCCEEDED — активирован тариф {}",
                            session.getPublicId(), session.getProductCode());
                } else {
                    balanceService.purchaseFromPayment(session.getUserId(), session.getCredits(), session.getId());
                    log.info("Платёж {} SUCCEEDED — баланс пополнен на {} кредит(ов)",
                            session.getPublicId(), session.getCredits());
                }
            }
            case CANCELED, FAILED -> {
                session.setStatus(remote.status());
                session.setCanceledAt(remote.canceledAt() != null ? remote.canceledAt() : Instant.now());
                session.setFailureReason(remote.failureReason());
                sessionRepository.save(session);
                log.info("Платёж {} терминал {}: {}", session.getPublicId(), remote.status(), remote.failureReason());
            }
            default -> log.info("Платёж {} ещё PENDING у провайдера — оставляем как есть", session.getPublicId());
        }
    }

    /**
     * Сверка платежа с данными провайдера перед пополнением (защита от подделки webhook'а): сумма,
     * валюта и {@code providerPaymentId} должны совпасть с локальной сессией.
     */
    private void verifyMatchesProvider(PaymentSession session, ProviderPaymentStatus remote) {
        if (remote.providerPaymentId() != null
                && !remote.providerPaymentId().equals(session.getProviderPaymentId())) {
            throw new ComplianceValidationException(
                    "providerPaymentId платежа " + session.getPublicId() + " не совпадает с провайдером");
        }
        if (remote.amount() != null && session.getAmount().compareTo(remote.amount()) != 0) {
            throw new ComplianceValidationException(
                    "Сумма платежа " + session.getPublicId() + " не совпадает с провайдером: локально "
                            + session.getAmount() + ", удалённо " + remote.amount());
        }
        if (remote.currency() != null && !session.getCurrency().equalsIgnoreCase(remote.currency())) {
            throw new ComplianceValidationException(
                    "Валюта платежа " + session.getPublicId() + " не совпадает с провайдером");
        }
    }
}
