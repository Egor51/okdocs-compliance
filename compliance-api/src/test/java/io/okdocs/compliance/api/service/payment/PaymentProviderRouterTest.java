package io.okdocs.compliance.api.service.payment;

import io.okdocs.compliance.contracts.enums.PaymentProvider;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentProviderRouterTest {

    private PaymentProviderRouter router;

    @BeforeEach
    void setUp() {
        // Зарегистрирован только YooKassa-адаптер (как в текущей итерации).
        PaymentProviderAdapter yooKassa = new StubAdapter(PaymentProvider.YOOKASSA);
        router = new PaymentProviderRouter(List.of(yooKassa));
    }

    @Test
    void ruWithNullProviderResolvesToYooKassa() {
        assertThat(router.resolve("ru", null)).isEqualTo(PaymentProvider.YOOKASSA);
    }

    @Test
    void ruWithExplicitYooKassaResolves() {
        assertThat(router.resolve("ru", PaymentProvider.YOOKASSA)).isEqualTo(PaymentProvider.YOOKASSA);
    }

    @Test
    void ruWithLocaleRegionTagStillResolves() {
        assertThat(router.resolve("ru-RU", null)).isEqualTo(PaymentProvider.YOOKASSA);
    }

    @Test
    void enLocaleUnsupportedInThisIteration() {
        assertThatThrownBy(() -> router.resolve("en", null))
                .isInstanceOf(ComplianceValidationException.class);
    }

    @Test
    void ruWithStripeUnsupportedForLocale() {
        assertThatThrownBy(() -> router.resolve("ru", PaymentProvider.STRIPE))
                .isInstanceOf(ComplianceValidationException.class);
    }

    @Test
    void adapterReturnsRegisteredAdapter() {
        assertThat(router.adapter(PaymentProvider.YOOKASSA).provider()).isEqualTo(PaymentProvider.YOOKASSA);
    }

    @Test
    void adapterThrowsForUnregisteredProvider() {
        assertThatThrownBy(() -> router.adapter(PaymentProvider.STRIPE))
                .isInstanceOf(ComplianceValidationException.class);
    }

    /** Минимальный адаптер-заглушка: важен только {@link #provider()} для регистрации в роутере. */
    private record StubAdapter(PaymentProvider provider) implements PaymentProviderAdapter {
        @Override
        public ProviderPayment createPayment(io.okdocs.compliance.persistence.billing.PaymentSession session,
                                             PaymentChargeContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderPaymentStatus fetchStatus(io.okdocs.compliance.persistence.billing.PaymentSession session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WebhookResult parseWebhook(Object payload) {
            throw new UnsupportedOperationException();
        }
    }
}
