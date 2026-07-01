package io.okdocs.compliance.api.service.payment;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.service.ScanBalanceService;
import io.okdocs.compliance.contracts.enums.PricingPlanCode;
import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaidPlanServiceTest {

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private ScanBalanceService balanceService;
    @Mock
    private ComplianceApiProperties properties;

    private PaidPlanService service;

    private final ComplianceApiProperties.Plan planQuotas =
            new ComplianceApiProperties.Plan(null); // дефолты: FREE=0, PRO=30, BUSINESS=200

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new PaidPlanService(userRepository, balanceService, properties);
        lenient().when(properties.plan()).thenReturn(planQuotas);
    }

    @Test
    void mapsPricingCodeToUserPlanByName() {
        assertThat(PaidPlanService.toUserPlan(PricingPlanCode.PRO)).isEqualTo(UserPlan.PRO);
        assertThat(PaidPlanService.toUserPlan(PricingPlanCode.BUSINESS)).isEqualTo(UserPlan.BUSINESS);
        assertThat(PaidPlanService.isPaidPlanProduct(PricingPlanCode.PRO)).isTrue();
        assertThat(PaidPlanService.isPaidPlanProduct(PricingPlanCode.ONE_REPORT)).isFalse();
    }

    @Test
    void activateFromPaymentSetsPlanAndGrantsQuota() {
        AppUser user = user(7L, UserPlan.FREE, null);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        UUID paymentId = UUID.randomUUID();

        service.activateFromPayment(7L, PricingPlanCode.PRO, paymentId);

        assertThat(user.getPlan()).isEqualTo(UserPlan.PRO);
        assertThat(user.getPlanRenewsAt()).isAfter(Instant.now());
        verify(userRepository).save(user);
        verify(balanceService).grantMonthlyFromPayment(7L, 30, paymentId);
    }

    @Test
    void upgradeProToBusinessStartsImmediately() {
        AppUser user = user(7L, UserPlan.PRO, Instant.now().plus(20, ChronoUnit.DAYS));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        UUID paymentId = UUID.randomUUID();

        service.activateFromPayment(7L, PricingPlanCode.BUSINESS, paymentId);

        assertThat(user.getPlan()).isEqualTo(UserPlan.BUSINESS);
        verify(balanceService).grantMonthlyFromPayment(7L, 200, paymentId);
    }

    @Test
    void activeDowngradeRaceDoesNotChangePlanNorGrantQuota() {
        // Защита от гонки: платёж PRO дошёл до активации при активном BUSINESS — план НЕ понижаем и
        // квоту НЕ выдаём (иначе higher-tier за цену PRO либо урезали бы активный период). Ручной разбор.
        AppUser user = user(7L, UserPlan.BUSINESS, Instant.now().plus(20, ChronoUnit.DAYS));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        UUID paymentId = UUID.randomUUID();

        service.activateFromPayment(7L, PricingPlanCode.PRO, paymentId);

        assertThat(user.getPlan()).isEqualTo(UserPlan.BUSINESS); // не понизили
        verify(userRepository, never()).save(user);
        verify(balanceService, never()).grantMonthlyFromPayment(org.mockito.ArgumentMatchers.anyLong(),
                anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void validatePurchasableRejectsActiveDowngrade() {
        AppUser user = user(7L, UserPlan.BUSINESS, Instant.now().plus(20, ChronoUnit.DAYS));

        assertThatThrownBy(() -> service.validatePurchasable(user, PricingPlanCode.PRO))
                .isInstanceOf(ComplianceValidationException.class);
    }

    @Test
    void validatePurchasableAllowsUpgradeAndSamePlan() {
        AppUser business = user(7L, UserPlan.BUSINESS, Instant.now().plus(20, ChronoUnit.DAYS));
        AppUser pro = user(8L, UserPlan.PRO, Instant.now().plus(20, ChronoUnit.DAYS));

        // upgrade PRO→BUSINESS, повтор BUSINESS→BUSINESS — без исключений.
        service.validatePurchasable(pro, PricingPlanCode.BUSINESS);
        service.validatePurchasable(business, PricingPlanCode.BUSINESS);
    }

    @Test
    void validatePurchasableAllowsProWhenBusinessPeriodExpired() {
        // Истёкший BUSINESS не блокирует покупку PRO.
        AppUser user = user(7L, UserPlan.BUSINESS, Instant.now().minus(1, ChronoUnit.DAYS));
        service.validatePurchasable(user, PricingPlanCode.PRO);
    }

    @Test
    void activateFromPaymentRejectsNonPlanProduct() {
        assertThatThrownBy(() -> service.activateFromPayment(7L, PricingPlanCode.ONE_REPORT, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(balanceService, never()).grantMonthlyFromPayment(org.mockito.ArgumentMatchers.anyLong(),
                anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void applyPlanSetsPeriodForPaidAndNullsForFree() {
        AppUser user = user(7L, UserPlan.BUSINESS, Instant.now().plus(20, ChronoUnit.DAYS));

        // FREE → planRenewsAt обнуляется (инвариант: FREE не имеет платного периода).
        service.applyPlan(user, UserPlan.FREE);
        assertThat(user.getPlan()).isEqualTo(UserPlan.FREE);
        assertThat(user.getPlanRenewsAt()).isNull();

        // PRO → ставится конец периода now+30d.
        service.applyPlan(user, UserPlan.PRO);
        assertThat(user.getPlan()).isEqualTo(UserPlan.PRO);
        assertThat(user.getPlanRenewsAt()).isAfter(Instant.now());
    }

    private AppUser user(Long id, UserPlan plan, Instant renewsAt) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setPlan(plan);
        u.setPlanRenewsAt(renewsAt);
        return u;
    }
}
