package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.remediation.RemediationLeadRequest;
import io.okdocs.compliance.contracts.remediation.RemediationRequestStatus;
import io.okdocs.compliance.persistence.remediation.RemediationLead;
import io.okdocs.compliance.persistence.remediation.RemediationLeadRepository;
import io.okdocs.compliance.mail.notification.MailNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemediationLeadServiceTest {

    @Mock private RemediationLeadRepository repository;
    @Mock private RateLimitService rateLimitService;
    @Mock private MailNotificationService mailNotificationService;

    private RemediationLeadService service;

    @BeforeEach
    void setUp() {
        service = new RemediationLeadService(
                repository, rateLimitService, mailNotificationService, "support@okdocs.io");
    }

    @Test
    void validatesNormalizesAndStoresPublicLeadWithoutReturningPersonalData() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-16T08:00:00Z");
        RemediationLead stored = new RemediationLead();
        stored.setId(id);
        stored.setStatus(RemediationRequestStatus.NEW);
        stored.setCreatedAt(createdAt);

        when(repository.findFirstByContactEmailIgnoreCaseAndSiteDomainAndStatusInOrderByCreatedAtDesc(
                eq("customer@example.com"), eq("okdocs.io"), any()))
                .thenReturn(Optional.of(stored));

        var response = service.create(new RemediationLeadRequest(
                "okdocs.io/path", "  Иван Иванов  ", " Customer@Example.COM ",
                " +7 999 123-45-67 ", "RU", true), "203.0.113.7");

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.status()).isEqualTo(RemediationRequestStatus.NEW);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        verify(rateLimitService).checkRemediationRequestAllowed("203.0.113.7");
        verify(repository).insertIfAbsent(
                any(UUID.class), eq("https://okdocs.io/path"), eq("okdocs.io"),
                eq("Иван Иванов"), eq("customer@example.com"), eq("+7 999 123-45-67"),
                eq("ru"), eq("NEW"), any(Instant.class), eq("203.0.113.7"),
                any(Instant.class));
        verify(mailNotificationService).enqueueRemediationRequest(
                id, "support@okdocs.io", "https://okdocs.io/path", "Иван Иванов",
                "customer@example.com", "+7 999 123-45-67", createdAt, "ru");
    }

    @Test
    void rejectsMissingConsentBeforeRateLimitAndUrlResolution() {
        var request = new RemediationLeadRequest(
                "okdocs.io", "Иван", "ivan@example.com", "", "ru", false);

        assertThatThrownBy(() -> service.create(request, "203.0.113.7"))
                .isInstanceOf(ComplianceValidationException.class)
                .hasMessageContaining("согласие");

        verifyNoInteractions(repository, rateLimitService, mailNotificationService);
    }

    @Test
    void stopsBeforePersistenceWhenIpRateLimitIsExceeded() {
        var request = new RemediationLeadRequest(
                "okdocs.io", "Иван", "ivan@example.com", "", "ru", true);
        var error = new io.okdocs.compliance.contracts.exception.ComplianceRateLimitException(
                "Слишком много заявок");
        org.mockito.Mockito.doThrow(error)
                .when(rateLimitService).checkRemediationRequestAllowed("203.0.113.7");

        assertThatThrownBy(() -> service.create(request, "203.0.113.7"))
                .isSameAs(error);

        verifyNoInteractions(repository, mailNotificationService);
    }
}
