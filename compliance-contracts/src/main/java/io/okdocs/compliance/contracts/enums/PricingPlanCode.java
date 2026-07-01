package io.okdocs.compliance.contracts.enums;

/**
 * Коммерческий продукт, который показывается в pricing-каталоге UI.
 * <p>
 * Не равен {@link UserPlan}: {@code ONE_REPORT} — разовая покупка отчёта, а не тариф аккаунта.
 */
public enum PricingPlanCode {
    ONE_REPORT,
    PRO,
    BUSINESS
}
