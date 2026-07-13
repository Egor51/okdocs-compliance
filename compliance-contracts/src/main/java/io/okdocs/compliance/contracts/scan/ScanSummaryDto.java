package io.okdocs.compliance.contracts.scan;

/**
 * Сводка по наблюдаемым (`CONFIRMED`/`DETECTED`) рискам. `totalPotentialFine` — legacy-поле:
 * всегда {@code null}; свободный текст findings нельзя юридически корректно суммировать.
 * {@code sanctionExposure} даёт коммерческий headline по максимальному одному релевантному сценарию
 * и структурированные детали, не складывая разные составы.
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
