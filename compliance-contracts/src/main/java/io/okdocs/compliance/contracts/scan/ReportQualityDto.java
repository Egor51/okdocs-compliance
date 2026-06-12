package io.okdocs.compliance.contracts.scan;

import java.util.List;

/** Сводка качества отчёта: сколько правил пройдено, провалено и не проверено. */
public record ReportQualityDto(
        int passed,
        int failed,
        int notEvaluated,
        List<PositiveCheckDto> positiveChecks
) {
}
