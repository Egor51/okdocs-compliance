package io.okdocs.compliance.contracts.enums;

/**
 * Слой правовых требований, к которому относится правило. В отличие от {@link ScanJurisdiction}
 * (выбор заказчика — «по какому закону проверяем целиком»), layer — это атомарный набор требований:
 * один скан может активировать несколько слоёв.
 * <p>
 * Пример: DE-скан активирует слои {@code EU} (GDPR/ePrivacy baseline) + {@code DE} (TDDDG/BDSG
 * overlay). Правило с {@code supportedLayers = {EU}} запустится на сканах EU/DE/FR/ES, но не на UK/RU.
 * Маппинг {@code ScanJurisdiction → Set<JurisdictionLayer>} задаёт {@link JurisdictionProfiles}.
 */
public enum JurisdictionLayer {
    RU,
    EU,
    UK,
    DE,
    FR,
    ES
}
