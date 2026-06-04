package io.okdocs.compliance.contracts.enums;

/**
 * Уверенность в finding'е (ось достоверности нарушения). Отлична от {@link RegistryStatus},
 * который описывает статус внешнего lookup'а, а не достоверность finding'а.
 */
public enum VerificationStatus {
    DETECTED,
    UNVERIFIED,
    CONFIRMED,
    FALSE_POSITIVE
}
