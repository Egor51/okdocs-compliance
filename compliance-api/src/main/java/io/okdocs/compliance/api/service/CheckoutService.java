package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.enums.CheckoutStatus;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.payment.CheckoutRequest;
import io.okdocs.compliance.contracts.payment.CheckoutResponse;
import io.okdocs.compliance.persistence.billing.CheckoutSession;
import io.okdocs.compliance.persistence.billing.CheckoutSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Checkout из кабинета + идемпотентный webhook оплаты (F.4).
 * <p>
 * {@link #createCheckout} создаёт сессию ДО оплаты (денег ещё нет). {@link #handleWebhook} —
 * единственный надёжный факт оплаты: в одной транзакции пополняет баланс, запускает premium-скан и
 * помечает сессию consumed. При сбое старта скана внутренняя транзакция откатывается целиком
 * (включая purchase — деньги не «зависают» кредитом), а внешний обработчик ОТДЕЛЬНОЙ транзакцией
 * помечает сессию {@code PAID_FAILED_TO_START} для retry-job (F.14) — деньги не теряются.
 */
@Slf4j
@Service
public class CheckoutService {

    private final CheckoutSessionRepository sessionRepository;
    private final ScanBalanceService balanceService;
    private final ScanCommandService scanCommandService;
    private final UrlValidatorService urlValidator;
    /** Self-proxy для вызова @Transactional методов через AOP (self-invocation иначе не перехватывается). */
    private final CheckoutService self;

    public CheckoutService(CheckoutSessionRepository sessionRepository,
                           ScanBalanceService balanceService,
                           ScanCommandService scanCommandService,
                           UrlValidatorService urlValidator,
                           @Lazy CheckoutService self) {
        this.sessionRepository = sessionRepository;
        this.balanceService = balanceService;
        this.scanCommandService = scanCommandService;
        this.urlValidator = urlValidator;
        this.self = self;
    }

    /**
     * Создать checkout-сессию от authenticated USER (F.12). Валидирует недоверенный prefill
     * (siteUrl/jurisdiction) уже здесь, чтобы не плодить заведомо нерабочие сессии. Активация
     * (баланс/скан) — НЕ здесь, а по webhook'у после оплаты.
     */
    @Transactional
    public CheckoutResponse createCheckout(Long userId, CheckoutRequest request) {
        UrlValidatorService.ValidatedUrl validated = urlValidator.validate(request.siteUrl());
        ScanJurisdiction jurisdiction = ScanCommandService.parseJurisdiction(request.jurisdiction());

        CheckoutSession session = new CheckoutSession();
        session.setUserId(userId);
        session.setSiteUrl(validated.normalizedUrl());
        session.setSiteDomain(validated.domain());
        session.setJurisdiction(jurisdiction);
        session.setPromoCode(request.promoCode());
        session.setStatus(CheckoutStatus.CREATED);
        session = sessionRepository.save(session);

        // MVP-каркас: реальная интеграция провайдера (confirmationUrl/подпись/сумма) — F.16.
        // Сейчас отдаём детерминированный placeholder, чтобы фронт-флоу и webhook можно было тестить.
        String confirmationUrl = "/checkout/" + session.getId() + "/pay";
        log.info("Checkout создан: id={} userId={} domain={}", session.getId(), userId, validated.domain());
        return new CheckoutResponse(session.getId(), confirmationUrl);
    }

    /**
     * Обработать webhook оплаты (F.14/F.15). Идемпотентно: повторная доставка того же платежа
     * (по {@code provider+providerPaymentId} или уже consumed-сессии) не делает второй purchase/скан.
     *
     * @param checkoutId        id сессии (из metadata платежа)
     * @param provider          платёжный провайдер
     * @param providerPaymentId idempotency-ключ платежа от провайдера
     */
    public void handleWebhook(UUID checkoutId, String provider, String providerPaymentId) {
        // Идемпотентность по ключу провайдера ДО блокировки: отсекаем дёшево ТОЛЬКО терминально
        // обработанный платёж (PAID_CONSUMED). Сессию PAID_FAILED_TO_START (оплата прошла, но скан
        // не стартовал) НЕ пропускаем — повторный webhook должен дать шанс retry внутри consume (P2).
        if (providerPaymentId != null) {
            var byKey = sessionRepository.findByProviderAndProviderPaymentId(provider, providerPaymentId);
            if (byKey.isPresent() && byKey.get().getStatus() == CheckoutStatus.PAID_CONSUMED) {
                log.info("Webhook повтор (provider={} paymentId={}) — уже consumed, пропуск", provider, providerPaymentId);
                return;
            }
        }
        try {
            self.consume(checkoutId, provider, providerPaymentId);
        } catch (PremiumStartFailedException e) {
            // Оплата прошла, но запуск скана упал: внутренняя транзакция (purchase+start) откатилась.
            // ОТДЕЛЬНОЙ транзакцией фиксируем платёж как ожидающий retry — деньги не теряются.
            self.markFailedToStart(checkoutId, provider, providerPaymentId);
        }
    }

    /**
     * Атомарная активация: lock сессии → if consumed return → purchase(+1) → internal premium-start
     * → mark consumed. Всё в одной транзакции: сбой старта откатывает и purchase.
     */
    @Transactional
    public void consume(UUID checkoutId, String provider, String providerPaymentId) {
        CheckoutSession session = sessionRepository.findWithLockById(checkoutId)
                .orElseThrow(() -> new ComplianceValidationException("Checkout-сессия не найдена: " + checkoutId));

        if (session.getStatus() == CheckoutStatus.PAID_CONSUMED) {
            // Конкурентный webhook уже всё сделал, пока мы ждали lock — идемпотентность.
            log.info("Checkout {} уже consumed — пропуск", checkoutId);
            return;
        }
        // CREATED (первый webhook) и PAID_FAILED_TO_START (retry после сбоя старта) — оба проходят
        // сюда: повторяем атомарный purchase+start. Дубля purchase нет — прошлый при сбое откатился.

        balanceService.purchase(session.getUserId(), 1);

        UUID scanId;
        try {
            scanId = scanCommandService.startInternalPremiumScan(
                    session.getUserId(), session.getSiteUrl(), session.getJurisdiction());
        } catch (RuntimeException e) {
            // Помечаем причину для внешнего обработчика; бросок откатывает purchase выше.
            log.error("Premium-start упал для checkout {} — откат purchase, уйдёт в retry", checkoutId, e);
            throw new PremiumStartFailedException(checkoutId, e);
        }

        session.setProvider(provider);
        session.setProviderPaymentId(providerPaymentId);
        session.setPremiumScanId(scanId);
        session.setStatus(CheckoutStatus.PAID_CONSUMED);
        session.setConsumedAt(java.time.Instant.now());
        sessionRepository.save(session);
        log.info("Checkout {} consumed: scanId={}", checkoutId, scanId);
    }

    /** Отдельная транзакция: зафиксировать оплаченный, но не стартовавший платёж для retry-job. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailedToStart(UUID checkoutId, String provider, String providerPaymentId) {
        sessionRepository.findById(checkoutId).ifPresent(session -> {
            if (session.getStatus() != CheckoutStatus.PAID_CONSUMED) {
                session.setProvider(provider);
                session.setProviderPaymentId(providerPaymentId);
                session.setStatus(CheckoutStatus.PAID_FAILED_TO_START);
                sessionRepository.save(session);
            }
        });
    }

    /** Маркер сбоя старта: триггерит fail-статус во внешнем обработчике, не утекает наружу. */
    static class PremiumStartFailedException extends RuntimeException {
        PremiumStartFailedException(UUID checkoutId, Throwable cause) {
            super("Не удалось запустить premium-скан для checkout " + checkoutId, cause);
        }
    }
}
