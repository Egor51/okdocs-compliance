package io.okdocs.compliance.contracts.scan;

import java.util.List;

/**
 * Маркетингово-понятное представление санкционного риска. Headline — максимум одного наиболее
 * строгого релевантного сценария (`MAX_RELEVANT_SCENARIO`), а не сумма всех findings.
 */
public record SanctionExposureDto(
        String headline,
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
        return new SanctionExposureDto(headline, maximumRelevantAmount, currency,
                calculationMethod, scenariosAreNotSummed, requiresLegalQualification, List.of());
    }
}
