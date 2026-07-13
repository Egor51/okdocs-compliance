package io.okdocs.compliance.contracts.scan;

/**
 * Сводка по наблюдаемым (`CONFIRMED`/`DETECTED`) рискам. `totalPotentialFine` — legacy-поле:
 * всегда {@code null}, пока report v2 не введёт структурированные сценарии санкций. Свободный текст
 * findings нельзя юридически корректно суммировать.
 */
public record ScanSummaryDto(
        int critical,
        int high,
        int medium,
        int low,
        String totalPotentialFine
) {
}
