package io.okdocs.compliance.api.service.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.api.service.PricingPlanCatalogService;
import io.okdocs.compliance.api.service.PricingPlanCatalogService.TopUpPricing;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaPaymentObject;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaWebhookPayload;
import io.okdocs.compliance.contracts.enums.BillingPeriod;
import io.okdocs.compliance.contracts.enums.PaymentProvider;
import io.okdocs.compliance.contracts.enums.PaymentStatus;
import io.okdocs.compliance.contracts.enums.PricingPlanCode;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.payment.CreatePaymentRequest;
import io.okdocs.compliance.contracts.payment.CreatePaymentResponse;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.billing.PaymentSession;
import io.okdocs.compliance.persistence.billing.PaymentSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentSessionRepository sessionRepository;
    @Mock
    private PricingPlanCatalogService catalogService;
    @Mock
    private PaymentProviderRouter router;
    @Mock
    private PaymentSessionWriter sessionWriter;
    @Mock
    private PaymentActivationService activationService;
    @Mock
    private ReturnUrlValidator returnUrlValidator;
    @Mock
    private PaidPlanService paidPlanService;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PaymentProviderAdapter adapter;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(sessionRepository, catalogService, router,
                sessionWriter, activationService, returnUrlValidator, paidPlanService, userRepository,
                new ObjectMapper());
        lenient().when(catalogService.normalizeLocalePublic(any())).thenReturn("ru");
        lenient().when(returnUrlValidator.resolve(any())).thenReturn("https://app/return");
        lenient().when(sessionWriter.createPending(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createsPendingYooKassaPaymentForOneReport() {
        stubUser(7L, "buyer@example.com");
        when(router.resolve("ru", null)).thenReturn(PaymentProvider.YOOKASSA);
        when(catalogService.resolveTopUpPricing(PricingPlanCode.ONE_REPORT, "ru"))
                .thenReturn(Optional.of(oneReportPricing()));
        when(router.adapter(PaymentProvider.YOOKASSA)).thenReturn(adapter);
        when(adapter.createPayment(any(), any())).thenReturn(new ProviderPayment(
                "yk-123", null, "https://yookassa/pay/yk-123", Instant.now().plusSeconds(3600), "{}"));
        when(sessionWriter.markPending(any(), any())).thenAnswer(inv -> {
            ProviderPayment pp = inv.getArgument(1);
            PaymentSession s = pendingSession("990.00", "RUB", 1);
            s.setProviderPaymentId(pp.providerPaymentId());
            s.setConfirmationUrl(pp.confirmationUrl());
            s.setExpiresAt(pp.expiresAt());
            return s;
        });

        CreatePaymentResponse response = service.createPayment(7L,
                new CreatePaymentRequest(PricingPlanCode.ONE_REPORT, null, "ru", null));

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.provider()).isEqualTo(PaymentProvider.YOOKASSA);
        assertThat(response.providerPaymentId()).isEqualTo("yk-123");
        assertThat(response.confirmationUrl()).isEqualTo("https://yookassa/pay/yk-123");
        assertThat(response.credits()).isEqualTo(1);
        assertThat(response.amount()).isEqualByComparingTo("990.00");
        assertThat(response.currency()).isEqualTo("RUB");
        assertThat(response.paymentPublicId()).isNotNull();
        // Внешний вызов идёт МЕЖДУ createPending и markPending (вне общей транзакции).
        verify(sessionWriter).createPending(any());
        verify(sessionWriter).markPending(any(), any());
    }

    @Test
    void providerFailureMarksSessionCreateFailedAndThrows() {
        stubUser(7L, "buyer@example.com");
        when(router.resolve("ru", null)).thenReturn(PaymentProvider.YOOKASSA);
        when(catalogService.resolveTopUpPricing(PricingPlanCode.ONE_REPORT, "ru"))
                .thenReturn(Optional.of(oneReportPricing()));
        when(router.adapter(PaymentProvider.YOOKASSA)).thenReturn(adapter);
        when(adapter.createPayment(any(), any())).thenThrow(new RuntimeException("yk timeout"));

        assertThatThrownBy(() -> service.createPayment(7L,
                new CreatePaymentRequest(PricingPlanCode.ONE_REPORT, null, "ru", null)))
                .isInstanceOf(ComplianceValidationException.class);

        // Неопределённый результат create → non-terminal CREATE_FAILED (не markPending).
        verify(sessionWriter).markCreateFailed(any(), any());
        verify(sessionWriter, never()).markPending(any(), any());
    }

    @Test
    void createsPaidPlanPaymentForPro() {
        stubUser(7L, "buyer@example.com");
        when(router.resolve("ru", null)).thenReturn(PaymentProvider.YOOKASSA);
        when(catalogService.resolveTopUpPricing(PricingPlanCode.PRO, "ru"))
                .thenReturn(Optional.of(new TopUpPricing(PricingPlanCode.PRO, BillingPeriod.MONTH,
                        30, new BigDecimal("4990.00"), "RUB", "PRO")));
        when(router.adapter(PaymentProvider.YOOKASSA)).thenReturn(adapter);
        when(adapter.createPayment(any(), any())).thenReturn(new ProviderPayment(
                "yk-pro", null, "https://yookassa/pay/yk-pro", null, "{}"));
        when(sessionWriter.markPending(any(), any())).thenAnswer(inv -> {
            PaymentSession s = pendingSession("4990.00", "RUB", 30);
            s.setProductCode(PricingPlanCode.PRO);
            s.setProviderPaymentId("yk-pro");
            s.setConfirmationUrl("https://yookassa/pay/yk-pro");
            return s;
        });

        CreatePaymentResponse response = service.createPayment(7L,
                new CreatePaymentRequest(PricingPlanCode.PRO, null, "ru", null));

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.productCode()).isEqualTo(PricingPlanCode.PRO);
        assertThat(response.credits()).isEqualTo(30);
        // PRO покупаем — guard на downgrade вызван при создании.
        verify(paidPlanService).validatePurchasable(any(), eq(PricingPlanCode.PRO));
        verify(sessionWriter).markPending(any(), any());
    }

    @Test
    void rejectsDowngradeBusinessToProAtCreate() {
        stubUser(7L, "buyer@example.com");
        when(router.resolve("ru", null)).thenReturn(PaymentProvider.YOOKASSA);
        when(catalogService.resolveTopUpPricing(PricingPlanCode.PRO, "ru"))
                .thenReturn(Optional.of(new TopUpPricing(PricingPlanCode.PRO, BillingPeriod.MONTH,
                        30, new BigDecimal("4990.00"), "RUB", "PRO")));
        // Активный BUSINESS → downgrade-валидатор бросает (fail-fast до денег).
        org.mockito.Mockito.doThrow(new ComplianceValidationException("downgrade"))
                .when(paidPlanService).validatePurchasable(any(), eq(PricingPlanCode.PRO));

        assertThatThrownBy(() -> service.createPayment(7L,
                new CreatePaymentRequest(PricingPlanCode.PRO, null, "ru", null)))
                .isInstanceOf(ComplianceValidationException.class);

        verify(sessionWriter, never()).createPending(any());
        verify(adapter, never()).createPayment(any(), any());
    }

    @Test
    void rejectsUnknownProduct() {
        // Гард отвергает продукт, который не top-up и не paid-plan (мисроутинг included_reports).
        stubUser(7L, "buyer@example.com");
        when(router.resolve("ru", null)).thenReturn(PaymentProvider.YOOKASSA);
        when(catalogService.resolveTopUpPricing(PricingPlanCode.ONE_REPORT, "ru"))
                .thenReturn(Optional.of(new TopUpPricing(PricingPlanCode.ONE_REPORT, BillingPeriod.MONTH,
                        1, new BigDecimal("990.00"), "RUB", "1 отчёт"))); // ONE_REPORT, но MONTH → несогласованно

        assertThatThrownBy(() -> service.createPayment(7L,
                new CreatePaymentRequest(PricingPlanCode.ONE_REPORT, null, "ru", null)))
                .isInstanceOf(ComplianceValidationException.class);
        verify(sessionWriter, never()).createPending(any());
    }

    @Test
    void rejectsYooKassaWhenUserHasNoEmail() {
        stubUser(7L, null);
        when(router.resolve("ru", null)).thenReturn(PaymentProvider.YOOKASSA);
        when(catalogService.resolveTopUpPricing(PricingPlanCode.ONE_REPORT, "ru"))
                .thenReturn(Optional.of(oneReportPricing()));

        assertThatThrownBy(() -> service.createPayment(7L,
                new CreatePaymentRequest(PricingPlanCode.ONE_REPORT, null, "ru", null)))
                .isInstanceOf(ComplianceValidationException.class);
        verify(sessionWriter, never()).createPending(any());
    }

    @Test
    void webhookDelegatesActivationForKnownPendingPayment() {
        PaymentSession session = pendingSession("990.00", "RUB", 1);
        when(router.adapter(PaymentProvider.YOOKASSA)).thenReturn(adapter);
        when(adapter.parseWebhook(any())).thenReturn(
                new WebhookResult(PaymentProvider.YOOKASSA, "yk-123", null, "payment.succeeded"));
        when(sessionRepository.findByProviderAndProviderPaymentId(PaymentProvider.YOOKASSA, "yk-123"))
                .thenReturn(Optional.of(session));

        service.handleYooKassaWebhook(payload("yk-123"));

        verify(activationService).activate(eq(session.getId()), eq(adapter));
        // providerPaymentId уже есть → recovery-привязка не вызывается.
        verify(sessionWriter, never()).attachProviderPaymentId(any(), any());
    }

    @Test
    void webhookRecoversProviderPaymentIdWhenSessionMissingIt() {
        // CREATE_FAILED-сессия без providerPaymentId, найдена по publicId; webhook несёт id → recovery.
        PaymentSession session = pendingSession("990.00", "RUB", 1);
        session.setProviderPaymentId(null);
        session.setStatus(PaymentStatus.CREATE_FAILED);
        UUID publicId = session.getPublicId();
        when(router.adapter(PaymentProvider.YOOKASSA)).thenReturn(adapter);
        when(adapter.parseWebhook(any())).thenReturn(
                new WebhookResult(PaymentProvider.YOOKASSA, "yk-777", publicId, "payment.succeeded"));
        when(sessionRepository.findByProviderAndProviderPaymentId(PaymentProvider.YOOKASSA, "yk-777"))
                .thenReturn(Optional.empty());
        when(sessionRepository.findByPublicId(publicId)).thenReturn(Optional.of(session));
        when(sessionWriter.attachProviderPaymentId(session.getId(), "yk-777")).thenReturn(true);

        service.handleYooKassaWebhook(payload("yk-777"));

        verify(sessionWriter).attachProviderPaymentId(session.getId(), "yk-777");
        verify(activationService).activate(eq(session.getId()), eq(adapter));
    }

    @Test
    void duplicateWebhookDoesNotActivateViaTerminalGuard() {
        PaymentSession session = pendingSession("990.00", "RUB", 1);
        session.setStatus(PaymentStatus.SUCCEEDED); // уже обработан
        when(router.adapter(PaymentProvider.YOOKASSA)).thenReturn(adapter);
        when(adapter.parseWebhook(any())).thenReturn(
                new WebhookResult(PaymentProvider.YOOKASSA, "yk-123", null, "payment.succeeded"));
        when(sessionRepository.findByProviderAndProviderPaymentId(PaymentProvider.YOOKASSA, "yk-123"))
                .thenReturn(Optional.of(session));

        service.handleYooKassaWebhook(payload("yk-123"));

        verify(activationService, never()).activate(any(), any());
    }

    @Test
    void unknownPaymentWebhookIsNoOp() {
        when(router.adapter(PaymentProvider.YOOKASSA)).thenReturn(adapter);
        when(adapter.parseWebhook(any())).thenReturn(
                new WebhookResult(PaymentProvider.YOOKASSA, "yk-unknown", null, "payment.succeeded"));
        when(sessionRepository.findByProviderAndProviderPaymentId(PaymentProvider.YOOKASSA, "yk-unknown"))
                .thenReturn(Optional.empty());

        service.handleYooKassaWebhook(payload("yk-unknown"));

        verify(activationService, never()).activate(any(), any());
    }

    // --- helpers ---

    private void stubUser(Long id, String email) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setEmail(email);
        lenient().when(userRepository.findById(id)).thenReturn(Optional.of(user));
    }

    private PaymentSession pendingSession(String amount, String currency, int credits) {
        PaymentSession s = new PaymentSession();
        s.setId(UUID.randomUUID());
        s.setPublicId(UUID.randomUUID());
        s.setUserId(42L);
        s.setProvider(PaymentProvider.YOOKASSA);
        s.setProviderPaymentId("yk-123");
        s.setStatus(PaymentStatus.PENDING);
        s.setProductCode(PricingPlanCode.ONE_REPORT);
        s.setLocale("ru");
        s.setCredits(credits);
        s.setAmount(new BigDecimal(amount));
        s.setCurrency(currency);
        return s;
    }

    private TopUpPricing oneReportPricing() {
        return new TopUpPricing(PricingPlanCode.ONE_REPORT, BillingPeriod.ONE_TIME, 1,
                new BigDecimal("990.00"), "RUB", "1 отчёт");
    }

    private YooKassaWebhookPayload payload(String providerPaymentId) {
        var object = new YooKassaPaymentObject(providerPaymentId, "succeeded", null, null,
                true, null, null, null, null, null);
        return new YooKassaWebhookPayload("payment.succeeded", object);
    }
}
