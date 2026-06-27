package io.okdocs.compliance.contracts.enums;

/**
 * Тип движения в леджере баланса сканов.
 * <ul>
 *   <li>{@code PLAN_GRANT} — месячная квота по тарифу;</li>
 *   <li>{@code PURCHASE} — докупленные сканы, начисленные после оплаты;</li>
 *   <li>{@code DEBIT} — списание за скан;</li>
 *   <li>{@code REFUND} — возврат при FAILED;</li>
 *   <li>{@code ADMIN_ADJUST} — ручная корректировка админом;</li>
 *   <li>{@code EXPIRE} — сгорание неиспользованной месячной квоты (в MVP не используется, задел).</li>
 * </ul>
 */
public enum BalanceTxnType {
    PLAN_GRANT,
    PURCHASE,
    DEBIT,
    REFUND,
    ADMIN_ADJUST,
    EXPIRE
}
