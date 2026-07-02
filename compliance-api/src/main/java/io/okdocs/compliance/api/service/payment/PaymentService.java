package io.okdocs.compliance.api.service.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.api.service.PricingPlanCatalogService;
import io.okdocs.compliance.api.service.PricingPlanCatalogService.TopUpPricing;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaWebhookPayload;
import io.okdocs.compliance.contracts.enums.BillingPeriod;
import io.okdocs.compliance.contracts.enums.PaymentProvider;
import io.okdocs.compliance.contracts.enums.PaymentStatus;
import io.okdocs.compliance.contracts.enums.PricingPlanCode;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.exception.ForbiddenResourceException;
import io.okdocs.compliance.contracts.payment.CreatePaymentRequest;
import io.okdocs.compliance.contracts.payment.CreatePaymentResponse;
import io.okdocs.compliance.contracts.payment.PaymentStatusResponse;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.billing.PaymentSession;
import io.okdocs.compliance.persistence.billing.PaymentSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Нейтральный сервис платежей (Balance-first, docs/PLAN-payments.md). Оркеструет lifecycle платёжной
 * сессии; конкретный провайдер скрыт за {@link PaymentProviderAdapter}, транзакционные записи — в
 * {@link PaymentSessionWriter}, активация под блокировкой — в {@link PaymentActivationService}.
 * <p>
 * Webhook НЕ запускает скан — успешная оплата лишь пополняет {@code purchasedRemaining}; premium-скан
 * юзер запускает отдельно через {@code /api/cabinet/scans}.
 * <p>
 * <b>Создание платежа не держит транзакцию во время внешнего вызова провайдера</b> (P1-фикс «orphan»):
 * tx1 CREATED commit → provider.createPayment вне tx → tx2 PENDING, либо non-terminal CREATE_FAILED
 * при неопределённой ошибке create (webhook/recovery потом доводит платёж).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String PRODUCT_TYPE_BALANCE = "SCAN_BALANCE";
    private static final String PRODUCT_TYPE_PLAN = "PAID_PLAN";

    /**
     * Продукты, разрешённые для разового пополнения баланса. Явный allowlist (а не «любой ONE_TIME»):
     * будущий разовый продукт может быть НЕ для баланса — добавлять сюда осознанно (SCAN_PACK_5/20 и т.п.).
     */
    private static final Set<PricingPlanCode> TOP_UP_PRODUCTS = Set.of(PricingPlanCode.ONE_REPORT);

    private final PaymentSessionRepository sessionRepository;
    private final PricingPlanCatalogService catalogService;
    private final PaymentProviderRouter router;
    private final PaymentSessionWriter sessionWriter;
    private final PaymentActivationService activationService;
    private final ReturnUrlValidator returnUrlValidator;
    private final PaidPlanService paidPlanService;
    private final AppUserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * Создать платёж. Поддерживает два типа продукта: top-up баланса ({@code ONE_REPORT}) и активацию
     * тарифа ({@code PRO}/{@code BUSINESS}); тип определяет ветку доменной активации при оплате.
     * Downgrade BUSINESS→PRO в активном периоде отвергается здесь (fail-fast, до денег).
     * <p>
     * Не {@code @Transactional}: внешний вызов провайдера идёт МЕЖДУ двумя независимыми транзакциями,
     * чтобы провайдерский платёж не «осиротел» при откате локального commit'а.
     */
    public CreatePaymentResponse createPayment(Long userId, CreatePaymentRequest request) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ComplianceValidationException("Пользователь не найден: " + userId));

        String locale = catalogService.normalizeLocalePublic(request.locale());
        PaymentProvider provider = router.resolve(locale, request.provider());

        // Резолв продукта+цены отдельным read-tx → detached value object (без LazyInit вне tx).
        TopUpPricing pricing = catalogService.resolveTopUpPricing(request.productCode(), locale)
                .orElseThrow(() -> new ComplianceValidationException(
                        "Продукт недоступен: " + request.productCode()));
        guardPurchasableProduct(pricing);

        // Downgrade BUSINESS→PRO в активном периоде запрещён — fail-fast ДО создания платежа (до денег).
        if (PaidPlanService.isPaidPlanProduct(request.productCode())) {
            paidPlanService.validatePurchasable(user, request.productCode());
        }

        if (provider == PaymentProvider.YOOKASSA && (user.getEmail() == null || user.getEmail().isBlank())) {
            // Фискальный чек YooKassa (54-ФЗ) требует email плательщика.
            throw new ComplianceValidationException(
                    "Для оплаты через YooKassa требуется email в профиле");
        }

        // Валидируем returnUrl ДО любых записей и внешних вызовов (fail-fast, анти-open-redirect).
        String returnUrl = returnUrlValidator.resolve(request.returnUrl());

        // Явные id/publicId до save: metadata содержит paymentPublicId, нужный ещё на этапе CREATED.
        PaymentSession session = new PaymentSession();
        session.setId(UUID.randomUUID());
        session.setPublicId(UUID.randomUUID());
        session.setUserId(userId);
        session.setProvider(provider);
        session.setStatus(PaymentStatus.CREATED);
        session.setProductCode(pricing.code());
        session.setLocale(locale);
        session.setMarket("ru".equals(locale) ? "RU" : "INTL");
        session.setCredits(pricing.includedReports());
        session.setAmount(pricing.priceAmount());
        session.setCurrency(pricing.currency());
        session.setIdempotenceKey(UUID.randomUUID().toString());
        session.setMetadataJson(buildMetadata(session));

        // Транзакция 1: фиксируем CREATED. После commit'а платёж точно есть в БД — webhook найдёт сессию.
        PaymentSession created = sessionWriter.createPending(session);
        UUID sessionId = created.getId();

        // Вне транзакции: внешний вызов провайдера.
        PaymentChargeContext context = new PaymentChargeContext(
                pricing.displayName(), user.getEmail(), returnUrl);
        ProviderPayment providerPayment;
        try {
            providerPayment = router.adapter(provider).createPayment(created, context);
        } catch (RuntimeException e) {
            // Результат create НЕОПРЕДЕЛЁН (timeout/разрыв мог прийти после фактического создания платежа
            // у провайдера). Ставим non-terminal CREATE_FAILED (НЕ terminal FAILED): webhook/recovery
            // ещё сможет довести платёж. Наружу отдаём ошибку.
            sessionWriter.markCreateFailed(sessionId, "Создание платежа у провайдера упало: " + e.getMessage());
            throw new ComplianceValidationException("Не удалось создать платёж у провайдера");
        }

        // Транзакция 2: provider-поля + PENDING.
        PaymentSession pending = sessionWriter.markPending(sessionId, providerPayment);

        log.info("Платёж создан: publicId={} userId={} provider={} product={} credits={} amount={} {}",
                pending.getPublicId(), userId, provider, pricing.code(), pending.getCredits(),
                pending.getAmount(), pending.getCurrency());

        return new CreatePaymentResponse(
                pending.getPublicId(),
                provider,
                pending.getProviderPaymentId(),
                pending.getConfirmationUrl(),
                pending.getStatus(),
                pending.getProductCode(),
                pending.getCredits(),
                pending.getAmount(),
                pending.getCurrency(),
                pending.getExpiresAt());
    }

    /**
     * Обработать webhook YooKassa (idempotent). Webhook лишь триггер — факт оплаты перепроверяет у
     * провайдера {@link PaymentActivationService}. Всегда «успешно» для провайдера (no-op на
     * неизвестном/повторе), чтобы он не зацикливал доставку.
     */
    public void handleYooKassaWebhook(YooKassaWebhookPayload payload) {
        PaymentProviderAdapter adapter = router.adapter(PaymentProvider.YOOKASSA);
        WebhookResult result = adapter.parseWebhook(payload);

        PaymentSession session = findSession(PaymentProvider.YOOKASSA,
                result.providerPaymentId(), result.paymentPublicId());
        if (session == null) {
            log.info("Webhook YooKassa: платёж не найден (paymentId={} publicId={} event={}) — игнор",
                    result.providerPaymentId(), result.paymentPublicId(), result.rawEvent());
            return;
        }
        // Дешёвый terminal-guard ДО блокировки: уже обработанный платёж не трогаем.
        if (session.getStatus().isTerminal()) {
            log.info("Webhook YooKassa повтор по {} — статус {}, пропуск", session.getPublicId(), session.getStatus());
            return;
        }
        // Recovery: create вернул неопределённость (CREATE_FAILED / нет providerPaymentId), но webhook
        // несёт providerPaymentId → привязываем под lock, чтобы fetchStatus имел по чему спрашивать.
        if (session.getProviderPaymentId() == null && result.providerPaymentId() != null) {
            if (!sessionWriter.attachProviderPaymentId(session.getId(), result.providerPaymentId())) {
                log.info("Webhook YooKassa: не удалось привязать providerPaymentId к {} — пропуск",
                        session.getPublicId());
                return;
            }
        }
        activationService.activate(session.getId(), adapter);
    }

    /**
     * Статус платежа (owner-only). Для не-терминального платежа с известным providerPaymentId освежает
     * статус у провайдера (pull-activation: готовит TON/reconciliation). НЕ {@code @Transactional}:
     * активация — своя REQUIRES_NEW write-tx в {@link PaymentActivationService}, чтобы не присоединять
     * запись SUCCEEDED + пополнение к read-контексту.
     */
    public PaymentStatusResponse getPaymentStatus(Long userId, UUID publicId) {
        PaymentSession session = sessionRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ComplianceValidationException("Платёж не найден: " + publicId));
        if (!session.getUserId().equals(userId)) {
            throw new ForbiddenResourceException("Платёж принадлежит другому пользователю");
        }
        if (!session.getStatus().isTerminal() && session.getProviderPaymentId() != null) {
            activationService.activate(session.getId(), router.adapter(session.getProvider()));
            session = sessionRepository.findByPublicId(publicId).orElseThrow();
        }
        return new PaymentStatusResponse(
                session.getPublicId(),
                session.getProvider(),
                session.getProviderPaymentId(),
                session.getStatus(),
                session.getProductCode(),
                session.getCredits(),
                session.getPaidAt(),
                session.getCanceledAt(),
                session.getFailureReason());
    }

    private PaymentSession findSession(PaymentProvider provider, String providerPaymentId, UUID publicId) {
        if (providerPaymentId != null) {
            var byProvider = sessionRepository.findByProviderAndProviderPaymentId(provider, providerPaymentId);
            if (byProvider.isPresent()) {
                return byProvider.get();
            }
        }
        if (publicId != null) {
            return sessionRepository.findByPublicId(publicId).orElse(null);
        }
        return null;
    }

    /**
     * Допустим ли продукт к покупке и согласованы ли его данные с типом активации:
     * <ul>
     *   <li>top-up (ONE_REPORT) — {@code ONE_TIME}, кредиты идут в {@code purchased_remaining};</li>
     *   <li>paid plan (PRO/BUSINESS) — {@code MONTH}, активирует тариф аккаунта.</li>
     * </ul>
     * Любой другой продукт (или несогласованный billingPeriod) → 400 — защита от тихого
     * мисроутинга included_reports не в тот «карман».
     */
    private void guardPurchasableProduct(TopUpPricing pricing) {
        boolean topUp = TOP_UP_PRODUCTS.contains(pricing.code());
        boolean paidPlan = PaidPlanService.isPaidPlanProduct(pricing.code());
        if (!topUp && !paidPlan) {
            throw new ComplianceValidationException(
                    "Продукт " + pricing.code() + " недоступен для покупки");
        }
        if (topUp && pricing.billingPeriod() != BillingPeriod.ONE_TIME) {
            throw new ComplianceValidationException(
                    "Продукт " + pricing.code() + " не является разовым");
        }
        if (paidPlan && pricing.billingPeriod() != BillingPeriod.MONTH) {
            throw new ComplianceValidationException(
                    "Тариф " + pricing.code() + " должен быть месячным");
        }
        if (pricing.includedReports() <= 0) {
            throw new ComplianceValidationException(
                    "Продукт " + pricing.code() + " не содержит отчётов");
        }
    }

    private String buildMetadata(PaymentSession session) {
        try {
            String productType = PaidPlanService.isPaidPlanProduct(session.getProductCode())
                    ? PRODUCT_TYPE_PLAN : PRODUCT_TYPE_BALANCE;
            return objectMapper.writeValueAsString(Map.of(
                    "productType", productType,
                    "paymentPublicId", session.getPublicId().toString(),
                    "userId", session.getUserId(),
                    "productCode", session.getProductCode().name(),
                    "credits", session.getCredits()));
        } catch (JsonProcessingException e) {
            log.warn("Не удалось сериализовать metadata платежа {}", session.getPublicId(), e);
            return null;
        }
    }
}
