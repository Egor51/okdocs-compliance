package io.okdocs.compliance.contracts.scan;

import jakarta.validation.constraints.NotBlank;

/**
 * Запуск сканирования. {@code tier} backend определяет сам (всегда стартует FREE), с фронта
 * не принимается.
 */
public record ScanRequest(
        @NotBlank String siteUrl
) {
}
