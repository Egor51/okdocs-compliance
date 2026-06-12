package io.okdocs.compliance.contracts.scan;

import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;

/** Outcome правила, сохранённый в diagnostics_json для отчёта и сравнения повторных сканов. */
public record RuleOutcomeDto(
        String code,
        String status,
        String title,
        FindingSeverity severity,
        FindingCategory category,
        String message
) {
}
