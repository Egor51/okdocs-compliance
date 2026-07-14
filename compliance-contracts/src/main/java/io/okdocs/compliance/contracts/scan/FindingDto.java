package io.okdocs.compliance.contracts.scan;

import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;

import java.util.List;

/**
 * Найденный риск/сигнал в отчёте; юридическое нарушение не считается автоматически установленным.
 * Поля {@code explanation}, {@code recommendation}, {@code evidence}
 * заполнены только в PREMIUM-отчёте; в FREE — {@code null} (маскируются при чтении по tier'у скана).
 * {@code affectedPages}: PREMIUM содержит обследованные страницы finding, FREE — пустой список;
 * старые JSON-снапшоты до добавления поля десериализуются с {@code null}.
 */
public record FindingDto(
        String code,
        FindingSeverity severity,
        FindingCategory category,
        String title,
        String fineAmount,
        String legalBasis,
        String explanation,
        String recommendation,
        String evidence,
        String sourceUrl,
        SourceType sourceType,
        Double confidence,
        VerificationStatus verificationStatus,
        EvidenceType evidenceType,
        List<String> matchedSignals,
        List<AffectedPageDto> affectedPages
) {
}
