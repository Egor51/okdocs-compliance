package io.okdocs.compliance.mail.subscription;

import io.okdocs.compliance.mail.config.ComplianceMailProperties;
import io.okdocs.compliance.persistence.mail.EmailSubscription;
import io.okdocs.compliance.persistence.mail.EmailSubscriptionRepository;
import io.okdocs.compliance.persistence.mail.EmailSubscriptionStatus;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailSubscriptionServiceTest {

    @Mock EmailSubscriptionRepository repository;
    EmailSubscriptionService service;

    @BeforeEach
    void setUp() {
        ComplianceMailProperties properties = new ComplianceMailProperties(
                false, null, null, "https://app.example", "payload-key", "unsubscribe-key",
                null, null, null, null, null);
        service = new EmailSubscriptionService(repository, properties);
    }

    @Test
    void subscribeNormalizesAndReactivatesAddress() {
        EmailSubscription existing = subscription();
        existing.setStatus(EmailSubscriptionStatus.UNSUBSCRIBED);
        when(repository.findByNormalizedEmail("mixed@example.com")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EmailSubscription result = service.subscribe(7L, " Mixed@Example.COM ", "en-US", "SCAN", "1.2.3.4");

        assertThat(result.getNormalizedEmail()).isEqualTo("mixed@example.com");
        assertThat(result.getStatus()).isEqualTo(EmailSubscriptionStatus.SUBSCRIBED);
        assertThat(result.getLocale()).isEqualTo("en");
        assertThat(result.getUnsubscribedAt()).isNull();
    }

    @Test
    void signedTokenUnsubscribesWithoutExposingEmail() {
        EmailSubscription subscription = subscription();
        when(repository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String token = service.tokenFor(subscription.getId());
        assertThat(token).doesNotContain(subscription.getEmail());
        assertThat(service.unsubscribe(token)).isTrue();
        assertThat(subscription.getStatus()).isEqualTo(EmailSubscriptionStatus.UNSUBSCRIBED);
        verify(repository).save(subscription);
    }

    @Test
    void rejectsTamperedToken() {
        String token = service.tokenFor(UUID.randomUUID());
        assertThatThrownBy(() -> service.unsubscribe(token + "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EmailSubscription subscription() {
        EmailSubscription value = new EmailSubscription();
        value.setId(UUID.randomUUID());
        value.setEmail("user@example.com");
        value.setNormalizedEmail("user@example.com");
        value.setLocale("ru");
        value.setStatus(EmailSubscriptionStatus.SUBSCRIBED);
        return value;
    }
}
