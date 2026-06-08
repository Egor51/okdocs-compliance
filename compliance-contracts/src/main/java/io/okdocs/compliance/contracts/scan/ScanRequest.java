package io.okdocs.compliance.contracts.scan;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Запуск сканирования. {@code tier} backend определяет сам (всегда стартует FREE), с фронта
 * не принимается.
 * <p>
 * {@code parentScanId} — для повторной проверки (re-scan): ссылка на предыдущий скан того же
 * домена. Backend валидирует, что родитель принадлежит тому же владельцу.
 */
public record ScanRequest(
        @NotBlank String siteUrl,
        @NotBlank String  jurisdiction,
        UUID parentScanId
) {
}
