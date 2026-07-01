package io.okdocs.compliance.api.service.payment;

import io.okdocs.compliance.contracts.enums.PaymentProvider;
import io.okdocs.compliance.contracts.enums.PaymentStatus;
import io.okdocs.compliance.persistence.billing.PaymentSession;
import io.okdocs.compliance.persistence.billing.PaymentSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentSessionWriterTest {

    @Mock
    private PaymentSessionRepository sessionRepository;

    @InjectMocks
    private PaymentSessionWriter writer;

    @Test
    void markCreateFailedSetsNonTerminalStatus() {
        PaymentSession session = session(PaymentStatus.CREATED, null);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        writer.markCreateFailed(session.getId(), "timeout");

        assertThat(session.getStatus()).isEqualTo(PaymentStatus.CREATE_FAILED);
        assertThat(session.getStatus().isTerminal()).isFalse();
        assertThat(session.getFailureReason()).isEqualTo("timeout");
    }

    @Test
    void attachProviderPaymentIdRecoversCreateFailedToPending() {
        PaymentSession session = session(PaymentStatus.CREATE_FAILED, null);
        session.setFailureReason("timeout");
        when(sessionRepository.findWithLockById(session.getId())).thenReturn(Optional.of(session));

        boolean ok = writer.attachProviderPaymentId(session.getId(), "yk-777");

        assertThat(ok).isTrue();
        assertThat(session.getProviderPaymentId()).isEqualTo("yk-777");
        assertThat(session.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(session.getFailureReason()).isNull();
    }

    @Test
    void attachProviderPaymentIdRecoversCreatedToPending() {
        // Гонка: webhook опередил markPending — сессия ещё CREATED, но provider id уже есть у webhook.
        PaymentSession session = session(PaymentStatus.CREATED, null);
        when(sessionRepository.findWithLockById(session.getId())).thenReturn(Optional.of(session));

        boolean ok = writer.attachProviderPaymentId(session.getId(), "yk-777");

        assertThat(ok).isTrue();
        assertThat(session.getProviderPaymentId()).isEqualTo("yk-777");
        assertThat(session.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void attachProviderPaymentIdIdempotentWhenAlreadySameId() {
        PaymentSession session = session(PaymentStatus.PENDING, "yk-777");
        when(sessionRepository.findWithLockById(session.getId())).thenReturn(Optional.of(session));

        assertThat(writer.attachProviderPaymentId(session.getId(), "yk-777")).isTrue();
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void attachProviderPaymentIdRejectsDifferentExistingId() {
        PaymentSession session = session(PaymentStatus.PENDING, "yk-AAA");
        when(sessionRepository.findWithLockById(session.getId())).thenReturn(Optional.of(session));

        // Расхождение id подозрительно — не перетираем, возвращаем false.
        assertThat(writer.attachProviderPaymentId(session.getId(), "yk-BBB")).isFalse();
        assertThat(session.getProviderPaymentId()).isEqualTo("yk-AAA");
    }

    @Test
    void attachProviderPaymentIdNoOpOnTerminalSession() {
        PaymentSession session = session(PaymentStatus.SUCCEEDED, null);
        when(sessionRepository.findWithLockById(session.getId())).thenReturn(Optional.of(session));

        assertThat(writer.attachProviderPaymentId(session.getId(), "yk-777")).isFalse();
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void attachProviderPaymentIdNullIsNoOp() {
        assertThat(writer.attachProviderPaymentId(UUID.randomUUID(), null)).isFalse();
        verify(sessionRepository, never()).findWithLockById(any());
    }

    private PaymentSession session(PaymentStatus status, String providerPaymentId) {
        PaymentSession s = new PaymentSession();
        s.setId(UUID.randomUUID());
        s.setPublicId(UUID.randomUUID());
        s.setUserId(42L);
        s.setProvider(PaymentProvider.YOOKASSA);
        s.setStatus(status);
        s.setProviderPaymentId(providerPaymentId);
        return s;
    }
}
