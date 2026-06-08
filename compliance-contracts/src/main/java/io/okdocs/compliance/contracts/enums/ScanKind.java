package io.okdocs.compliance.contracts.enums;

/**
 * Продуктовый flow скана — определяет режим выполнения в worker'е (в отличие от {@link ScanTier},
 * который про разблокировку отчёта при чтении).
 *
 * <ul>
 *   <li>{@code FREE_MARKETING} — публичный лид-магнит ({@code POST /api/free-scans}): 1 страница,
 *       только STATIC, без списания баланса, short retention, урезанный отчёт + CTA.</li>
 *   <li>{@code CABINET_PREMIUM} — рабочий скан в кабинете ({@code POST /api/cabinet/scans}): полный
 *       crawl, STATIC + DYNAMIC (dynamic required), списание 1 кредита, refund при FAILED.</li>
 * </ul>
 *
 * Source of truth — строка {@code ComplianceScan} в БД. Worker гейтит поведение по {@code kind},
 * не по producer-решениям в событии.
 */
public enum ScanKind {
    FREE_MARKETING,
    CABINET_PREMIUM
}
