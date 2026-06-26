package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.enums.CheckoutStatus;
import io.okdocs.compliance.contracts.enums.PaymentProvider;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.payment.CheckoutRequest;
import io.okdocs.compliance.contracts.payment.CheckoutResponse;
import io.okdocs.compliance.persistence.billing.CheckoutSession;
import io.okdocs.compliance.persistence.billing.CheckoutSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    @Mock private CheckoutSessionRepository sessionRepository;
    @Mock private ScanBalanceService balanceService;
    @Mock private ScanCommandService scanCommandService;
    @Mock private UrlValidatorService urlValidator;

    private CheckoutService service;

    private static final UUID CHECKOUT_ID = UUID.randomUUID();
    private static final PaymentProvider PROVIDER = PaymentProvider.STRIPE;
    private static final String PAYMENT_ID = "pi_123";

    @BeforeEach
    void setUp() {
        service = new CheckoutService(sessionRepository, balanceService, scanCommandService, urlValidator, null);
        // self → реальный инстанс (в проде @Lazy-прокси для @Transactional consume/markFailedToStart).
        service = new CheckoutService(sessionRepository, balanceService, scanCommandService, urlValidator, service);
    }

    // ---- createCheckout ----

    @Test
    void createCheckoutValidatesPrefillAndPersistsCreatedSession() {
        when(urlValidator.validate("example.ru"))
                .thenReturn(new UrlValidatorService.ValidatedUrl("https://example.ru", "example.ru"));
        when(scanCommandService.resolveEnabledJurisdiction("ru")).thenReturn(ScanJurisdiction.RU);
        when(sessionRepository.save(any(CheckoutSession.class))).thenAnswer(inv -> {
            CheckoutSession s = inv.getArgument(0);
            s.setId(CHECKOUT_ID);
            return s;
        });

        CheckoutResponse resp = service.createCheckout(5L, new CheckoutRequest("example.ru", "ru", null));

        assertThat(resp.checkoutId()).isEqualTo(CHECKOUT_ID);
        assertThat(resp.confirmationUrl()).contains(CHECKOUT_ID.toString());
        // Активация не происходит на создании сессии.
        verify(balanceService, never()).purchase(anyLong(), anyInt());
        verify(scanCommandService, never()).startInternalPremiumScan(anyLong(), any(), any());
    }

    @Test
    void createCheckoutRejectsInvalidJurisdiction() {
        when(urlValidator.validate(any()))
                .thenReturn(new UrlValidatorService.ValidatedUrl("https://example.ru", "example.ru"));
        when(scanCommandService.resolveEnabledJurisdiction("ATLANTIS"))
                .thenThrow(new ComplianceValidationException("Неизвестная юрисдикция скана: ATLANTIS"));

        assertThatThrownBy(() -> service.createCheckout(5L, new CheckoutRequest("example.ru", "ATLANTIS", null)))
                .isInstanceOf(ComplianceValidationException.class);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void createCheckoutRejectsInvalidUrl() {
        when(urlValidator.validate(any()))
                .thenThrow(new ComplianceValidationException("Адрес сайта недопустим (приватная сеть)"));

        assertThatThrownBy(() -> service.createCheckout(5L, new CheckoutRequest("http://127.0.0.1", "RU", null)))
                .isInstanceOf(ComplianceValidationException.class);
        verify(sessionRepository, never()).save(any());
    }

    // ---- handleWebhook: happy path ----

    @Test
    void webhookPurchasesStartsScanAndMarksConsumed() {
        CheckoutSession session = createdSession();
        when(sessionRepository.findByProviderAndProviderPaymentId(PROVIDER, PAYMENT_ID))
                .thenReturn(Optional.empty());
        when(sessionRepository.findWithLockById(CHECKOUT_ID)).thenReturn(Optional.of(session));
        UUID scanId = UUID.randomUUID();
        when(scanCommandService.startInternalPremiumScan(7L, "https://example.ru", ScanJurisdiction.RU))
                .thenReturn(scanId);

        service.handleWebhook(CHECKOUT_ID, PROVIDER, PAYMENT_ID);

        verify(balanceService).purchase(7L, 1);
        verify(scanCommandService).startInternalPremiumScan(7L, "https://example.ru", ScanJurisdiction.RU);
        assertThat(session.getStatus()).isEqualTo(CheckoutStatus.PAID_CONSUMED);
        assertThat(session.getPremiumScanId()).isEqualTo(scanId);
        assertThat(session.getProviderPaymentId()).isEqualTo(PAYMENT_ID);
        verify(sessionRepository).save(session);
    }

    // ---- idempotency ----

    @Test
    void webhookDuplicateByProviderKeyIsSkippedWhenAlreadyConsumed() {
        // Терминально обработанный платёж (PAID_CONSUMED) отсекается дёшево pre-check'ом, без lock.
        CheckoutSession consumed = createdSession();
        consumed.setStatus(CheckoutStatus.PAID_CONSUMED);
        when(sessionRepository.findByProviderAndProviderPaymentId(PROVIDER, PAYMENT_ID))
                .thenReturn(Optional.of(consumed));

        service.handleWebhook(CHECKOUT_ID, PROVIDER, PAYMENT_ID);

        verify(sessionRepository, never()).findWithLockById(any());
        verify(balanceService, never()).purchase(anyLong(), anyInt());
        verify(scanCommandService, never()).startInternalPremiumScan(anyLong(), any(), any());
    }

    @Test
    void webhookOnAlreadyConsumedSessionDoesNotPurchaseAgain() {
        // Конкурентный webhook выиграл lock и уже consume'нул — второй видит PAID_CONSUMED.
        CheckoutSession consumed = createdSession();
        consumed.setStatus(CheckoutStatus.PAID_CONSUMED);
        when(sessionRepository.findByProviderAndProviderPaymentId(PROVIDER, PAYMENT_ID))
                .thenReturn(Optional.empty());
        when(sessionRepository.findWithLockById(CHECKOUT_ID)).thenReturn(Optional.of(consumed));

        service.handleWebhook(CHECKOUT_ID, PROVIDER, PAYMENT_ID);

        verify(balanceService, never()).purchase(anyLong(), anyInt());
        verify(scanCommandService, never()).startInternalPremiumScan(anyLong(), any(), any());
    }

    // ---- failure path: premium-start падает ----

    @Test
    void webhookMarksFailedToStartWhenPremiumStartThrows() {
        CheckoutSession session = createdSession();
        when(sessionRepository.findByProviderAndProviderPaymentId(PROVIDER, PAYMENT_ID))
                .thenReturn(Optional.empty());
        when(sessionRepository.findWithLockById(CHECKOUT_ID)).thenReturn(Optional.of(session));
        when(scanCommandService.startInternalPremiumScan(anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("worker недоступен"));
        // markFailedToStart грузит сессию заново (отдельная транзакция).
        when(sessionRepository.findById(CHECKOUT_ID)).thenReturn(Optional.of(session));

        service.handleWebhook(CHECKOUT_ID, PROVIDER, PAYMENT_ID);

        // purchase был вызван (в проде откатится транзакцией); скан не consumed.
        verify(balanceService).purchase(7L, 1);
        assertThat(session.getStatus()).isEqualTo(CheckoutStatus.PAID_FAILED_TO_START);
        assertThat(session.getPremiumScanId()).isNull();
    }

    @Test
    void webhookDuplicateByProviderKeyRetriesWhenPreviousFailedToStart() {
        // P2: повтор webhook для PAID_FAILED_TO_START НЕ отсекается pre-check'ом — даём retry.
        CheckoutSession failed = createdSession();
        failed.setStatus(CheckoutStatus.PAID_FAILED_TO_START);
        failed.setProviderPaymentId(PAYMENT_ID);
        when(sessionRepository.findByProviderAndProviderPaymentId(PROVIDER, PAYMENT_ID))
                .thenReturn(Optional.of(failed));
        when(sessionRepository.findWithLockById(CHECKOUT_ID)).thenReturn(Optional.of(failed));
        UUID scanId = UUID.randomUUID();
        when(scanCommandService.startInternalPremiumScan(7L, "https://example.ru", ScanJurisdiction.RU))
                .thenReturn(scanId);

        service.handleWebhook(CHECKOUT_ID, PROVIDER, PAYMENT_ID);

        // Retry прошёл: purchase+start выполнены, статус стал consumed.
        verify(balanceService).purchase(7L, 1);
        verify(scanCommandService).startInternalPremiumScan(7L, "https://example.ru", ScanJurisdiction.RU);
        assertThat(failed.getStatus()).isEqualTo(CheckoutStatus.PAID_CONSUMED);
        assertThat(failed.getPremiumScanId()).isEqualTo(scanId);
    }

    @Test
    void webhookThrowsWhenSessionNotFound() {
        when(sessionRepository.findByProviderAndProviderPaymentId(any(), any())).thenReturn(Optional.empty());
        when(sessionRepository.findWithLockById(CHECKOUT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleWebhook(CHECKOUT_ID, PROVIDER, PAYMENT_ID))
                .isInstanceOf(ComplianceValidationException.class);
    }

    private CheckoutSession createdSession() {
        CheckoutSession s = new CheckoutSession();
        s.setId(CHECKOUT_ID);
        s.setUserId(7L);
        s.setSiteUrl("https://example.ru");
        s.setSiteDomain("example.ru");
        s.setJurisdiction(ScanJurisdiction.RU);
        s.setStatus(CheckoutStatus.CREATED);
        return s;
    }
}
