package io.okdocs.compliance.contracts.scan;

import io.okdocs.compliance.contracts.enums.FindingCategory;

/** Правило, которое автоматическая проверка не смогла подтвердить или опровергнуть. */
public record UnverifiedRuleDto(
        String code,
        String title,
        FindingCategory category,
        String reason
) {
}
