package io.okdocs.compliance.contracts.scan;

import java.time.LocalDate;
import java.util.List;

/**
 * Структурированный справочный сценарий административной ответственности. Это не назначенный и не
 * прогнозируемый штраф: применимость зависит от состава, субъекта, повторности и юридической
 * квалификации. Несколько сценариев отчёта не складываются арифметически.
 */
public record SanctionScenarioDto(
        String id,
        String label,
        List<String> relatedFindingCodes,
        String law,
        String article,
        String part,
        String subjectType,
        String recurrence,
        long minimumAmount,
        long maximumAmount,
        String currency,
        String applicability,
        String sourceUrl,
        LocalDate normVerifiedOn
) {
    public SanctionScenarioDto {
        relatedFindingCodes = relatedFindingCodes == null ? List.of() : List.copyOf(relatedFindingCodes);
    }
}
