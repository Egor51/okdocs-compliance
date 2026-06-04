package io.okdocs.compliance.contracts.enums;

/**
 * Статус проверки оператора в реестре РКН (ось внешнего lookup'а). {@code LOOKUP_FAILED} —
 * реестр недоступен. Правило мапит это в {@link VerificationStatus} finding'а.
 */
public enum RegistryStatus {
    FOUND,
    NOT_FOUND,
    LOOKUP_FAILED
}
