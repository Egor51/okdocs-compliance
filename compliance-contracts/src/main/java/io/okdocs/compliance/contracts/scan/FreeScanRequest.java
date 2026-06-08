package io.okdocs.compliance.contracts.scan;

import jakarta.validation.constraints.NotBlank;

/**
 * Запуск бесплатного маркетингового скана ({@code POST /api/free-scans}).
 * <p>
 * Публичный лид-магнит: проверяется только главная страница ({@code maxPages=1}, static-only),
 * баланс не трогается. Без {@code parentScanId} — у marketing-скана нет повторной проверки.
 */
public record FreeScanRequest(
        @NotBlank String siteUrl,
        @NotBlank String  jurisdiction
) {
}
