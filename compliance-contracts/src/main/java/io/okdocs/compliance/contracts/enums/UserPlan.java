package io.okdocs.compliance.contracts.enums;

/**
 * Тариф (подписка) аккаунта. Даёт месячную квоту сканов и premium-отчёты по умолчанию.
 * Отличается от {@link ScanTier}: {@code UserPlan} — свойство аккаунта, {@code ScanTier} —
 * уровень детализации конкретного скана.
 */
public enum UserPlan {
    FREE,
    PRO,
    BUSINESS
}
