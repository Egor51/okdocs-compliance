package io.okdocs.compliance.contracts.enums;

/**
 * Уровень доступа к отчёту. Скан всегда стартует {@code FREE}; {@code PREMIUM} покупается после
 * скана и раскрывает скрытые поля finding'ов (explanation/recommendation/evidence) при чтении.
 */
public enum ScanTier {
    FREE,
    PREMIUM
}
