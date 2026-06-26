package io.okdocs.compliance.contracts.enums;

import java.util.List;
import java.util.Set;

/**
 * Профиль юрисдикции: какие {@link JurisdictionLayer} активирует скан данной
 * {@link ScanJurisdiction}. Единственный источник истины о наследовании EU baseline.
 * <p>
 * DE/FR/ES → {@code {EU, <страна>}}: локальный overlay добавляет строгость поверх baseline, не
 * заменяет его. UK → {@code {UK}}: UK GDPR/PECR — отдельный режим, EU baseline не наследуется.
 * RU/EU → одиночный слой.
 * <p>
 * Используется в движке правил для гейта: правило запускается, если его {@code supportedLayers}
 * пересекается со слоями скана.
 */
public final class JurisdictionProfiles {

    private JurisdictionProfiles() {
    }

    /**
     * Слои, активируемые сканом данной юрисдикции.
     *
     * @throws IllegalArgumentException для устаревших значений без профиля (напр. {@code GM})
     */
    @SuppressWarnings("deprecation") // GM обязан быть в switch (exhaustive), пока не удалён из enum
    public static Set<JurisdictionLayer> layers(ScanJurisdiction jurisdiction) {
        return switch (jurisdiction) {
            case RU -> Set.of(JurisdictionLayer.RU);
            case EU -> Set.of(JurisdictionLayer.EU);
            case UK -> Set.of(JurisdictionLayer.UK);
            case DE -> Set.of(JurisdictionLayer.EU, JurisdictionLayer.DE);
            case FR -> Set.of(JurisdictionLayer.EU, JurisdictionLayer.FR);
            case ES -> Set.of(JurisdictionLayer.EU, JurisdictionLayer.ES);
            case GM -> throw new IllegalArgumentException(
                    "GM — устаревшая юрисдикция без профиля слоёв; см. PLAN-jurisdictions.md");
        };
    }

    /**
     * Слои в порядке приоритета метаданных: специфичный overlay → baseline. Резолвер метаданных
     * правила берёт первый слой, для которого есть запись: DE ищет сначала {@code DE}-метаданные,
     * затем падает на {@code EU}-baseline. Для одно-слойных юрисдикций — список из одного слоя.
     */
    @SuppressWarnings("deprecation") // GM обязан быть в switch (exhaustive), пока не удалён из enum
    public static List<JurisdictionLayer> layerPriority(ScanJurisdiction jurisdiction) {
        return switch (jurisdiction) {
            case RU -> List.of(JurisdictionLayer.RU);
            case EU -> List.of(JurisdictionLayer.EU);
            case UK -> List.of(JurisdictionLayer.UK);
            case DE -> List.of(JurisdictionLayer.DE, JurisdictionLayer.EU);
            case FR -> List.of(JurisdictionLayer.FR, JurisdictionLayer.EU);
            case ES -> List.of(JurisdictionLayer.ES, JurisdictionLayer.EU);
            case GM -> throw new IllegalArgumentException(
                    "GM — устаревшая юрисдикция без профиля слоёв; см. PLAN-jurisdictions.md");
        };
    }
}
