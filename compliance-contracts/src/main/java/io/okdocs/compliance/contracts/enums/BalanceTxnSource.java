package io.okdocs.compliance.contracts.enums;

/**
 * Из какого «кармана» баланса прошло движение скана.
 * <ul>
 *   <li>{@code MONTHLY} — месячная квота тарифа (сгорает в начале периода);</li>
 *   <li>{@code PURCHASED} — докупленные сканы (не сгорают).</li>
 * </ul>
 * Заполняется для {@code DEBIT} (какой карман списан) и {@code REFUND} (копирует source исходного
 * DEBIT по scanId). Для {@code PURCHASE}/{@code PLAN_GRANT}/{@code ADMIN_ADJUST}/{@code EXPIRE} — {@code null}.
 */
public enum BalanceTxnSource {
    MONTHLY,
    PURCHASED
}
