package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;

/** Сводный outcome правила для отчёта и re-scan diff, без page-level доказательств. */
public record RuleOutcome(
        String code,
        RuleOutcomeStatus status,
        String title,
        FindingSeverity severity,
        FindingCategory category,
        String message
) {
}
