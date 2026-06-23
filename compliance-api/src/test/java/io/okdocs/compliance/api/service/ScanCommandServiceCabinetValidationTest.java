package io.okdocs.compliance.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.contracts.enums.UserRole;
import io.okdocs.compliance.contracts.exception.AccessDeniedToScanException;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.scan.ScanRequest;
import io.okdocs.compliance.messaging.OutboxEventFactory;
import io.okdocs.compliance.persistence.outbox.OutboxEventRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScanReportRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import io.okdocs.compliance.persistence.scan.ScanEmailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F.3 §F11 — {@code /register?url=&jur=} приходит как <b>недоверенный</b> prefill. Premium-запуск
 * кабинета обязан валидировать {@code siteUrl}/{@code jurisdiction} и НЕ списывать кредит при
 * отклонении. Эти тесты фиксируют инвариант (он уже обеспечен общими validate/parseJurisdiction —
 * тут защита от регрессии): подмена URL/юрисдикции = ошибка валидации, не списание и не уязвимость.
 */
@ExtendWith(MockitoExtension.class)
class ScanCommandServiceCabinetValidationTest {

    @Mock private ComplianceScanRepository scanRepository;
    @Mock private ComplianceScanReportRepository scanReportRepository;
    @Mock private ScanEmailRepository scanEmailRepository;
    @Mock private OutboxEventRepository outboxRepository;
    @Mock private OutboxEventFactory outboxEventFactory;
    @Mock private ScanBalanceService balanceService;
    @Mock private RateLimitService rateLimitService;
    @Mock private UrlValidatorService urlValidator;
    @Mock private ScanMapper scanMapper;
    @Mock private ComplianceApiProperties properties;
    @Mock private ObjectMapper objectMapper;

    private ScanCommandService service;

    private final CompliancePrincipal user = CompliancePrincipal.user(1L, UserRole.USER);

    @BeforeEach
    void setUp() {
        service = new ScanCommandService(scanRepository, scanReportRepository, scanEmailRepository,
                outboxRepository, outboxEventFactory, balanceService, rateLimitService, urlValidator,
                scanMapper, properties, objectMapper);
    }

    @Test
    void rejectsUntrustedPrefillUrlAndDoesNotDebit() {
        // Подменённый/невалидный URL из prefill: validator бракует (SSRF/схема/резолв) до списания.
        when(urlValidator.validate("http://169.254.169.254/"))
                .thenThrow(new ComplianceValidationException("Адрес сайта недопустим (приватная сеть)"));

        assertThatThrownBy(() -> service.startCabinetScan(
                new ScanRequest("http://169.254.169.254/", "RU", null), "1.2.3.4", user))
                .isInstanceOf(ComplianceValidationException.class);

        verify(balanceService, never()).debit(anyLong(), any());
        verify(scanRepository, never()).save(any());
    }

    @Test
    void rejectsUnknownPrefillJurisdictionAndDoesNotDebit() {
        // URL валиден, но юрисдикция из prefill неизвестна → parseJurisdiction бракует, кредит цел.
        when(urlValidator.validate("https://example.ru"))
                .thenReturn(new UrlValidatorService.ValidatedUrl("https://example.ru", "example.ru"));

        assertThatThrownBy(() -> service.startCabinetScan(
                new ScanRequest("https://example.ru", "ATLANTIS", null), "1.2.3.4", user))
                .isInstanceOf(ComplianceValidationException.class);

        verify(balanceService, never()).debit(anyLong(), any());
        verify(scanRepository, never()).save(any());
    }

    @Test
    void internalPremiumScanDebitsAndStartsWithoutRateLimitOrPrincipal() {
        // F.13: webhook-путь без principal/IP/rate-limit. Валидирует URL, списывает кредит, ставит скан.
        when(urlValidator.validate("https://example.ru"))
                .thenReturn(new UrlValidatorService.ValidatedUrl("https://example.ru", "example.ru"));
        when(properties.scan()).thenReturn(
                new ComplianceApiProperties.Scan(null, null, 30, null, null));
        // publishScanRequested дёргает kafka-топик и фабрику outbox.
        when(properties.kafka()).thenReturn(new ComplianceApiProperties.KafkaTopics(
                new ComplianceApiProperties.KafkaTopics.Topic("scan.requested", "scan.completed", "scan.failed")));

        java.util.UUID scanId = service.startInternalPremiumScan(
                7L, "https://example.ru", io.okdocs.compliance.contracts.enums.ScanJurisdiction.RU);

        assertThat(scanId).isNotNull();
        verify(rateLimitService, never()).checkScanAllowed(any(), any());
        verify(balanceService).debit(7L, scanId);
        verify(scanRepository).save(any());
        verify(outboxRepository).save(any());
    }

    @Test
    void rejectsNonUserPrincipalBeforeAnyValidationOrDebit() {
        // Premium-запуск только для USER: guest с подделанным prefill даже не доходит до валидации.
        CompliancePrincipal guest = CompliancePrincipal.guest(java.util.UUID.randomUUID());

        assertThatThrownBy(() -> service.startCabinetScan(
                new ScanRequest("https://example.ru", "RU", null), "1.2.3.4", guest))
                .isInstanceOf(AccessDeniedToScanException.class);

        verify(rateLimitService, never()).checkScanAllowed(any(), any());
        verify(balanceService, never()).debit(anyLong(), any());
    }
}
