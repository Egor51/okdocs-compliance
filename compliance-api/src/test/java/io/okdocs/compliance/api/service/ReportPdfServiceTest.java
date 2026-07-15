package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.enums.UserRole;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.scan.AffectedPageDto;
import io.okdocs.compliance.contracts.scan.FindingDto;
import io.okdocs.compliance.contracts.scan.ReportQualityDto;
import io.okdocs.compliance.contracts.scan.ScanReportResponse;
import io.okdocs.compliance.contracts.scan.ScanSummaryDto;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportPdfServiceTest {

    @Mock private ScanCommandService scanCommandService;
    @Mock private ComplianceScanRepository scanRepository;
    @InjectMocks private ReportPdfService service;

    private final CompliancePrincipal principal = CompliancePrincipal.user(7L, UserRole.USER);

    @Test
    void generatesReadableRussianPdfForPremiumReport() throws Exception {
        UUID scanId = UUID.randomUUID();
        ScanReportResponse report = report(scanId, ScanTier.PREMIUM);
        ComplianceScan scan = new ComplianceScan();
        scan.setId(scanId);
        scan.setLocale("ru");
        when(scanCommandService.getReport(scanId, principal)).thenReturn(report);
        when(scanRepository.findById(scanId)).thenReturn(Optional.of(scan));

        ReportPdfService.PdfDocument pdf = service.generate(scanId, principal);

        assertThat(pdf.content()).startsWith('%', 'P', 'D', 'F');
        assertThat(pdf.filename()).isEqualTo("okdocs-example.ru-" + scanId + ".pdf");
        try (PDDocument document = Loader.loadPDF(pdf.content())) {
            assertThat(document.getNumberOfPages()).isPositive();
            assertThat(new PDFTextStripper().getText(document))
                    .contains("Отчёт о проверке", "https://example.ru", "КРИТИЧНЫЕ",
                            "КАК ИСПРАВИТЬ", "https://example.ru/login");
        }
        verify(scanCommandService).getReport(scanId, principal);
        verify(scanRepository).findById(scanId);
    }

    @Test
    void rejectsPdfForFreeReport() {
        UUID scanId = UUID.randomUUID();
        when(scanCommandService.getReport(scanId, principal))
                .thenReturn(report(scanId, ScanTier.FREE));

        assertThatThrownBy(() -> service.generate(scanId, principal))
                .isInstanceOf(ComplianceValidationException.class)
                .hasMessageContaining("полного отчёта");

        verifyNoInteractions(scanRepository);
    }

    private static ScanReportResponse report(UUID id, ScanTier tier) {
        Instant createdAt = Instant.parse("2026-07-15T08:00:00Z");
        return new ScanReportResponse(
                id,
                "https://example.ru",
                "example.ru",
                ScanJurisdiction.RU,
                ScanStatus.COMPLETED,
                73,
                tier,
                null,
                new ScanSummaryDto(1, 1, 1, 1, "до 500 000 ₽", null),
                List.of(finding()),
                null,
                new ReportQualityDto(12, 4, 2, List.of()),
                null,
                1_500L,
                createdAt,
                createdAt.plusSeconds(2));
    }

    private static FindingDto finding() {
        return new FindingDto(
                "AUTH-FOREIGN", FindingSeverity.HIGH, FindingCategory.FORMS,
                "Авторизация через иностранный сервис требует проверки оснований",
                "до 500 000 ₽", "ст. 12 152-ФЗ",
                "На странице обнаружен внешний сервис авторизации, который может получать идентификаторы пользователя.",
                "Проверьте состав передаваемых данных, основание обработки и раскройте трансграничную передачу в политике.",
                "Обнаружена кнопка входа через внешний сервис.", "https://example.ru/login",
                SourceType.HTML, .91, VerificationStatus.DETECTED,
                EvidenceType.STATIC_ANALYSIS, List.of("oauth", "login"),
                List.of(new AffectedPageDto(
                        "https://example.ru/login", "Внешняя кнопка входа", SourceType.HTML,
                        .91, VerificationStatus.DETECTED, EvidenceType.STATIC_ANALYSIS,
                        List.of("oauth"))));
    }
}
