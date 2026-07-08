package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.service.payment.PaymentService;
import io.okdocs.compliance.api.service.payment.yookassa.dto.YooKassaWebhookPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Webhook YooKassa публичный (у провайдера нет JWT), поэтому первый барьер — fail-closed shared-secret
 * в query-параметре {@code token} webhook-URL (кастомные header'ы YooKassa не поддерживает): без него
 * кто угодно подделкой JSON начислял бы себе кредиты.
 */
@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    private PaymentService paymentService;

    private final YooKassaWebhookPayload payload = new YooKassaWebhookPayload("payment.succeeded", null);

    private PaymentController controllerWithSecret(String configured) {
        var props = new ComplianceApiProperties(
                null, null, null, null, null, null, null, null,
                new ComplianceApiProperties.Payment(configured));
        return new PaymentController(paymentService, props);
    }

    @Test
    void processesWebhookWhenTokenMatches() {
        PaymentController controller = controllerWithSecret("s3cret");

        ResponseEntity<Void> resp = controller.yooKassaWebhook("s3cret", payload);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(paymentService).handleYooKassaWebhook(payload);
    }

    @Test
    void rejectsWrongToken() {
        PaymentController controller = controllerWithSecret("s3cret");

        ResponseEntity<Void> resp = controller.yooKassaWebhook("wrong", payload);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(paymentService, never()).handleYooKassaWebhook(any());
    }

    @Test
    void failsClosedWhenSecretNotConfigured() {
        PaymentController controller = controllerWithSecret("");

        ResponseEntity<Void> resp = controller.yooKassaWebhook("anything", payload);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(paymentService, never()).handleYooKassaWebhook(any());
    }

    @Test
    void rejectsMissingToken() {
        PaymentController controller = controllerWithSecret("s3cret");

        ResponseEntity<Void> resp = controller.yooKassaWebhook(null, payload);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(paymentService, never()).handleYooKassaWebhook(any());
    }
}
