package io.okdocs.compliance.api.service.payment;

import io.okdocs.compliance.api.service.ScanBalanceService;
import io.okdocs.compliance.contracts.enums.PaymentProvider;
import io.okdocs.compliance.contracts.enums.PaymentStatus;
import io.okdocs.compliance.contracts.enums.PricingPlanCode;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.persistence.billing.PaymentSession;
import io.okdocs.compliance.persistence.billing.PaymentSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentActivationServiceTest {

    @Mock
    private PaymentSessionRepository sessionRepository;
    @Mock
    private ScanBalanceService balanceService;
    @Mock
    private PaidPlanService paidPlanService;
    @Mock
    private PaymentProviderAdapter adapter;

    @InjectMocks
    private PaymentActivationService service;

    @Test
    void succeededCreditsBalanceOnce() {
        PaymentSession session = pending();
        when(sessionRepository.findWithLockById(session.getId())).thenReturn(Optional.of(session));
        when(adapter.fetchStatus(session)).thenReturn(succeeded("990.00", "RUB", "yk-123"));

        service.activate(session.getId(), adapter);

        assertThat(session.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(session.getPaidAt()).isNotNull();
        verify(balanceService).purchaseFromPayment(eq(42L), eq(1), eq(session.getId()));
        verify(paidPlanService, never()).activateFromPayment(any(), any(), any());
    }

    @Test
    void succeededPaidPlanActivatesPlanNotBalance() {
        PaymentSession session = pending();
        session.setProductCode(PricingPlanCode.PRO); // тарифный продукт → ветка PAID_PLAN
        when(sessionRepository.findWithLockById(session.getId())).thenReturn(Optional.of(session));
        when(adapter.fetchStatus(session)).thenReturn(succeeded("990.00", "RUB", "yk-123"));

        service.activate(session.getId(), adapter);

        assertThat(session.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(paidPlanService).activateFromPayment(eq(42L), eq(PricingPlanCode.PRO), eq(session.getId()));
        verify(balanceService, never()).purchaseFromPayment(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void terminalSessionUnderLockIsNoOp() {
        PaymentSession session = pending();
        session.setStatus(PaymentStatus.SUCCEEDED); // конкурент уже активировал
        when(sessionRepository.findWithLockById(session.getId())).thenReturn(Optional.of(session));

        service.activate(session.getId(), adapter);

        verify(adapter, never()).fetchStatus(any());
        verify(balanceService, never()).purchaseFromPayment(anyLong(), anyInt(), any());
    }

    @Test
    void amountMismatchRejectsAndDoesNotCredit() {
        PaymentSession session = pending();
        when(sessionRepository.findWithLockById(session.getId())).thenReturn(Optional.of(session));
        when(adapter.fetchStatus(session)).thenReturn(succeeded("1.00", "RUB", "yk-123"));

        assertThatThrownBy(() -> service.activate(session.getId(), adapter))
                .isInstanceOf(ComplianceValidationException.class);
        verify(balanceService, never()).purchaseFromPayment(anyLong(), anyInt(), any());
    }

    @Test
    void providerPaymentIdMismatchRejectsAndDoesNotCredit() {
        PaymentSession session = pending();
        when(sessionRepository.findWithLockById(session.getId())).thenReturn(Optional.of(session));
        when(adapter.fetchStatus(session)).thenReturn(succeeded("990.00", "RUB", "yk-OTHER"));

        assertThatThrownBy(() -> service.activate(session.getId(), adapter))
                .isInstanceOf(ComplianceValidationException.class);
        verify(balanceService, never()).purchaseFromPayment(anyLong(), anyInt(), any());
    }

    @Test
    void canceledDoesNotCredit() {
        PaymentSession session = pending();
        when(sessionRepository.findWithLockById(session.getId())).thenReturn(Optional.of(session));
        when(adapter.fetchStatus(session)).thenReturn(new ProviderPaymentStatus(
                PaymentStatus.CANCELED, "yk-123", null, null, null, Instant.now(), "expired"));

        service.activate(session.getId(), adapter);

        assertThat(session.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(session.getFailureReason()).isEqualTo("expired");
        verify(balanceService, never()).purchaseFromPayment(anyLong(), anyInt(), any());
    }

    @Test
    void pendingRemoteLeavesSessionUntouched() {
        PaymentSession session = pending();
        when(sessionRepository.findWithLockById(session.getId())).thenReturn(Optional.of(session));
        when(adapter.fetchStatus(session)).thenReturn(new ProviderPaymentStatus(
                PaymentStatus.PENDING, "yk-123", null, null, null, null, null));

        service.activate(session.getId(), adapter);

        assertThat(session.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(balanceService, never()).purchaseFromPayment(anyLong(), anyInt(), any());
    }

    private PaymentSession pending() {
        PaymentSession s = new PaymentSession();
        s.setId(UUID.randomUUID());
        s.setPublicId(UUID.randomUUID());
        s.setUserId(42L);
        s.setProvider(PaymentProvider.YOOKASSA);
        s.setProviderPaymentId("yk-123");
        s.setStatus(PaymentStatus.PENDING);
        s.setProductCode(PricingPlanCode.ONE_REPORT);
        s.setCredits(1);
        s.setAmount(new BigDecimal("990.00"));
        s.setCurrency("RUB");
        return s;
    }

    private ProviderPaymentStatus succeeded(String amount, String currency, String providerPaymentId) {
        return new ProviderPaymentStatus(PaymentStatus.SUCCEEDED, providerPaymentId,
                new BigDecimal(amount), currency, Instant.now(), null, null);
    }
}
