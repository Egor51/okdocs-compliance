package io.okdocs.compliance.contracts.scan;

/** Сводка по отчёту: количество findings по severity + суммарный диапазон штрафов. */
public record ScanSummaryDto(
        int critical,
        int high,
        int medium,
        int low,
        String totalPotentialFine
) {
}
