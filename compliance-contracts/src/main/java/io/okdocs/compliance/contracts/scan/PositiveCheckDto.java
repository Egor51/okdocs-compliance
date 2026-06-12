package io.okdocs.compliance.contracts.scan;

import io.okdocs.compliance.contracts.enums.FindingCategory;

/** Понятная пользователю успешная проверка в блоке "Что уже сделано хорошо". */
public record PositiveCheckDto(
        String code,
        String title,
        FindingCategory category,
        String message
) {
}
