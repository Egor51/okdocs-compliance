package io.okdocs.compliance.contracts.enums;

/**
 * Юрисдикция скана — «по какому закону проверяем» (152-ФЗ → {@code RU}, GDPR → {@code EU}).
 * Это выбор заказчика, а не страна хостинга ({@code hostCountry}).
 * <p>
 * DE/FR/ES запускают EU baseline + локальный overlay (см. {@link JurisdictionProfiles}); UK —
 * отдельная ветка (UK GDPR/PECR), EU baseline <b>не</b> наследует.
 */
public enum ScanJurisdiction {
    RU,
    EU,
    UK,
    DE,
    FR,
    ES,
    /**
     * @deprecated ошибочное значение из ранней модели. Удаляется отдельным PR после GM-аудита
     *             прод-данных (см. PLAN-jurisdictions.md). Не использовать в новом коде.
     */
    @Deprecated
    GM
}
