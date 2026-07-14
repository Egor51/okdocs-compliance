package io.okdocs.compliance.contracts.scan;

/**
 * Сводка по наблюдаемым (`CONFIRMED`/`DETECTED`) рискам. {@code totalPotentialFine} — читаемый
 * арифметический диапазон по структурированным релевантным сценариям: диапазоны независимых групп
 * нарушений складываются, взаимоисключающие альтернативы внутри группы учитываются один раз.
 * {@code sanctionExposure} содержит те же границы и детали каждого сценария.
 */
public record ScanSummaryDto(
        int critical,
        int high,
        int medium,
        int low,
        String totalPotentialFine,
        SanctionExposureDto sanctionExposure
) {
}
