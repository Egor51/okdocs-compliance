package io.okdocs.compliance.contracts.enums;

/**
 * Уровень доступа к отчёту. {@code FREE} маскирует premium-поля finding'ов; {@code PREMIUM}
 * раскрывает explanation/recommendation/evidence/sourceUrl. Для cabinet-скана кредит списывается
 * при запуске, поэтому отчёт сразу имеет {@code PREMIUM}; для free marketing остаётся {@code FREE}.
 */
public enum ScanTier {
    FREE,
    PREMIUM
}
