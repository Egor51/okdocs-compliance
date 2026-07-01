package io.okdocs.compliance.api.service.payment;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.service.ScanBalanceService;
import io.okdocs.compliance.contracts.enums.PricingPlanCode;
import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * Платные тарифы PRO/BUSINESS как разовая покупка месяца (docs/PLAN-payments.md, Этап 2).
 * <p>
 * Единое ядро активации тарифа ({@link #applyPlan}) переиспользуется и админом ({@code AdminService}),
 * и платёжной активацией ({@link #activateFromPayment}) — чтобы две точки выдачи плана не разъезжались.
 * Модель non-recurring: активация ставит {@code plan_renews_at = now+30d} как конец оплаченного периода
 * (продление — только новой оплатой; истечение завершает {@code MonthlyQuotaScheduler}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaidPlanService {

    private static final int PERIOD_DAYS = 30;

    /** Продукты, которые активируют тариф аккаунта (а не пополняют баланс). */
    public static final Set<PricingPlanCode> PAID_PLAN_PRODUCTS =
            Set.of(PricingPlanCode.PRO, PricingPlanCode.BUSINESS);

    private final AppUserRepository userRepository;
    private final ScanBalanceService balanceService;
    private final ComplianceApiProperties properties;

    /** Платёжный продукт является активацией тарифа (PRO/BUSINESS), а не balance top-up. */
    public static boolean isPaidPlanProduct(PricingPlanCode code) {
        return PAID_PLAN_PRODUCTS.contains(code);
    }

    /** Маппинг продукта каталога в тариф аккаунта (имена совпадают). */
    public static UserPlan toUserPlan(PricingPlanCode code) {
        return UserPlan.valueOf(code.name());
    }

    /**
     * Активировать тариф по оплате (вызывается из {@code PaymentActivationService} в транзакции
     * активации платежа). Идемпотентно по {@code paymentId} (PLAN_GRANT(payment_id) unique).
     * Downgrade BUSINESS→PRO в активном периоде запрещён fail-fast при создании; здесь — защита от
     * гонки: план НЕ понижаем и квоту НЕ выдаём (иначе выдали бы higher-tier за цену PRO либо урезали
     * активный BUSINESS-период). Платёж остаётся SUCCEEDED — деньги получены, разбор ручной
     * (manual/refund/reconciliation), не автоматическая выдача.
     */
    @Transactional
    public void activateFromPayment(Long userId, PricingPlanCode productCode, UUID paymentId) {
        if (!isPaidPlanProduct(productCode)) {
            throw new IllegalArgumentException("Не тарифный продукт: " + productCode);
        }
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ComplianceValidationException("Пользователь не найден: " + userId));
        UserPlan newPlan = toUserPlan(productCode);

        if (isActiveDowngrade(user, newPlan)) {
            // Не понижаем и НЕ выдаём квоту — требуется ручной разбор (refund/reconciliation).
            log.warn("Платёж {}: downgrade {}→{} при активном периоде — план не меняем, квоту НЕ выдаём, "
                            + "нужен ручной разбор (refund/reconciliation)",
                    paymentId, user.getPlan(), newPlan);
            return;
        }

        applyPlan(user, newPlan);
        // Квота выдаётся идемпотентно по платежу (защита от двойного webhook/poll).
        balanceService.grantMonthlyFromPayment(userId, properties.plan().quotaFor(newPlan), paymentId);
        log.info("Платёж {}: активирован тариф {} для юзера {} до {}",
                paymentId, newPlan, userId, user.getPlanRenewsAt());
    }

    /**
     * Общее ядро: выставить тариф аккаунта. Для PRO/BUSINESS ставит конец оплаченного периода
     * (now+30d); для FREE обнуляет {@code planRenewsAt} (инвариант: FREE не имеет платного периода,
     * иначе expire-job/семантика «paid period end» нарушаются). Квоту НЕ трогает — её начисляет
     * вызывающий (идемпотентно по платежу или прямо у админа). Вызывать в транзакции.
     */
    public void applyPlan(AppUser user, UserPlan plan) {
        user.setPlan(plan);
        if (plan == UserPlan.FREE) {
            user.setPlanRenewsAt(null);
        } else {
            user.setPlanRenewsAt(Instant.now().plus(PERIOD_DAYS, ChronoUnit.DAYS));
        }
        userRepository.save(user);
    }

    /** Активный период более высокого тарифа, который понижается новым продуктом. */
    private boolean isActiveDowngrade(AppUser user, UserPlan newPlan) {
        boolean active = user.getPlanRenewsAt() != null && user.getPlanRenewsAt().isAfter(Instant.now());
        return active && user.getPlan() == UserPlan.BUSINESS && newPlan == UserPlan.PRO;
    }

    /**
     * Проверка для fail-fast при СОЗДАНИИ платежа: можно ли купить {@code productCode} сейчас.
     * Бросает {@link ComplianceValidationException} (→400) на downgrade BUSINESS→PRO в активном периоде.
     */
    public void validatePurchasable(AppUser user, PricingPlanCode productCode) {
        if (isActiveDowngrade(user, toUserPlan(productCode))) {
            throw new ComplianceValidationException(
                    "Понижение тарифа BUSINESS→PRO в активном периоде недоступно");
        }
    }
}
