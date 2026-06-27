package io.okdocs.compliance.contracts.catalog;

import io.okdocs.compliance.contracts.enums.ScanJurisdiction;

import java.util.List;

/**
 * Публичное описание юрисдикции, доступной для запуска compliance scan.
 */
public record JurisdictionDto(
        ScanJurisdiction code,
        String slug,
        String displayName,
        String description,
        List<String> laws,
        String contentLanguage,
        boolean defaultJurisdiction,
        int sortOrder
) {
}
