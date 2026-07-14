package io.okdocs.compliance.contracts.scan;

import java.util.List;

/**
 * Маркетингово-понятное представление санкционного риска. Итоговый диапазон складывает независимые
 * группы нарушений, но не складывает взаимоисключающие альтернативы внутри группы (ИП/юрлицо,
 * первое/повторное нарушение).
 */
public record SanctionExposureDto(
        String headline,
        Long minimumRelevantAmount,
        Long maximumRelevantAmount,
        String currency,
        String calculationMethod,
        boolean scenariosAreNotSummed,
        boolean requiresLegalQualification,
        List<SanctionScenarioDto> scenarios
) {
    public SanctionExposureDto {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    }

    /** FREE сохраняет усиливающий headline, но детали квалификации остаются PREMIUM-ценностью. */
    public SanctionExposureDto headlineOnly() {
        return new SanctionExposureDto(headline, minimumRelevantAmount, maximumRelevantAmount, currency,
                calculationMethod, scenariosAreNotSummed, requiresLegalQualification, List.of());
    }
}
