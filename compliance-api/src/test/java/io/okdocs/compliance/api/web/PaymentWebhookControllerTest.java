package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.service.CheckoutService;
import io.okdocs.compliance.contracts.payment.PaymentWebhookRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P1 — webhook оплаты должен аутентифицироваться shared-secret'ом, иначе кто угодно подделкой JSON
 * получил бы бесплатный premium. Fail-closed при незаданном секрете.
 */
@ExtendWith(MockitoExtension.class)
class PaymentWebhookControllerTest {

    @Mock
    private CheckoutService checkoutService;

    private final PaymentWebhookRequest request =
            new PaymentWebhookRequest(UUID.randomUUID(), "STRIPE", "pi_123");

    private PaymentWebhookController controllerWithSecret(String configured) {
        var props = new ComplianceApiProperties(
                null, null, null, null, null, null, null, null,
                new ComplianceApiProperties.Payment(configured));
        return new PaymentWebhookController(checkoutService, props);
    }

    @Test
    void processesWebhookWhenSecretMatches() {
        PaymentWebhookController controller = controllerWithSecret("s3cret");

        ResponseEntity<Void> resp = controller.webhook("s3cret", request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(checkoutService).handleWebhook(request.checkoutId(), "STRIPE", "pi_123");
    }

    @Test
    void rejectsWrongSecret() {
        PaymentWebhookController controller = controllerWithSecret("s3cret");

        ResponseEntity<Void> resp = controller.webhook("wrong", request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(checkoutService, never()).handleWebhook(any(), any(), any());
    }

    @Test
    void rejectsMissingSecretHeader() {
        PaymentWebhookController controller = controllerWithSecret("s3cret");

        ResponseEntity<Void> resp = controller.webhook(null, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(checkoutService, never()).handleWebhook(any(), any(), any());
    }

    @Test
    void failClosedWhenSecretNotConfigured() {
        // Незаданный секрет → отвергаем ВСЁ (даже запрос без/с любым header), не открываем premium.
        PaymentWebhookController controller = controllerWithSecret(null);

        assertThat(controller.webhook(null, request).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.webhook("anything", request).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(checkoutService, never()).handleWebhook(any(), any(), any());
    }
}
