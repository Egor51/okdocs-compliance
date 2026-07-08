package io.okdocs.compliance.contracts.catalog;

import io.okdocs.compliance.contracts.enums.ScanJurisdiction;

import java.util.List;

/**
 * Публичное описание юрисдикции, доступной для запуска compliance scan.
 *
 * @param displayName    H1 на лендинге юрисдикции
 * @param description     H2 (подзаголовок) на лендинге
 * @param seoTitle        {@code <title>} страницы
 * @param seoDescription  {@code <meta name="description">}
 * @param countryName     полное название страны/региона ("Российская Федерация")
 */
public record JurisdictionDto(
        ScanJurisdiction code,
        String slug,
        String displayName,
        String description,
        String seoTitle,
        String seoDescription,
        String countryName,
        List<String> laws,
        String contentLanguage,
        boolean defaultJurisdiction,
        int sortOrder
) {
}
