package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.enums.JurisdictionLayer;
import io.okdocs.compliance.contracts.enums.JurisdictionProfiles;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Резолвер метаданных правила по {@code (code, jurisdiction)} для {@link FindingAssembler}.
 * <p>
 * <b>Multi-layer (§ PLAN-jurisdictions Фаза 1–3)</b>: один detector-{@code code}
 * ({@code MISSING_HSTS}) может иметь разные метаданные по слоям (RU 152-ФЗ / EU GDPR / UK PECR).
 * Метаданные индексируются по {@code (code, layer)} под <b>own-слоем</b> правила
 * ({@code definition().jurisdiction()}), а НЕ под всеми {@code supportedLayers()} (иначе shared
 * common-правило записало бы RU-тексты в слои EU/UK). Метаданные других слоёв того же кода
 * добавляются через {@code overlayMetadata}. Резолв идёт по приоритету слоёв скана
 * ({@link JurisdictionProfiles#layerPriority}): overlay → baseline, то есть DE-метаданные
 * перекрывают EU, при их отсутствии — fallback на EU.
 * <p>
 * ⏸ <b>MVP без БД-override</b>: возвращает дефолт из кода. Слияние с {@code rule_config} (БД-override,
 * §2.10, V010) отложено вместе с admin-редактированием правил — точка расширения: чтение
 * {@code RuleMetadataOverrideRepository} в кэш и наложение поверх дефолта, без смены сигнатуры.
 * <p>
 * <b>Код = источник истины о существовании правила</b>: список даёт {@link RulesConfiguration}
 * ({@code List<Rule>} + overlay). Факт с {@code code}, которого нет в коде, метаданных не получит
 * (assembler его отбросит). Создаётся {@code @Bean}'ом в {@link RulesConfiguration} (не autowire),
 * чтобы передать overlayMetadata.
 */
public class RuleMetadataResolver {

    /** code → (layer → метаданные правила в этом слое). */
    private final Map<String, Map<JurisdictionLayer, RuleDefinition>> definitionsByCodeAndLayer;

    public RuleMetadataResolver(List<Rule> rules) {
        this(rules, List.of());
    }

    /**
     * @param rules            детекторы; {@code definition()} даёт метаданные ОДНОГО (own) слоя —
     *                         слоя из {@code definition().jurisdiction()}. {@code supportedLayers()}
     *                         здесь не используется (это гейт движка): shared common-правило
     *                         ({@code {RU,EU,UK}}) регистрирует только RU-метаданные.
     * @param overlayMetadata  standalone-метаданные для других слоёв того же {@code code} (EU/UK для
     *                         shared common-детектора) — добавляются отдельно от правил (§ Фаза 3).
     *                         Каждая запись индексируется под своим {@code jurisdiction}-слоем.
     */
    public RuleMetadataResolver(List<Rule> rules, List<RuleDefinition> overlayMetadata) {
        Map<String, Map<JurisdictionLayer, RuleDefinition>> index = new HashMap<>();
        for (Rule rule : rules) {
            register(index, rule.definition());
        }
        for (RuleDefinition overlay : overlayMetadata) {
            register(index, overlay);
        }
        this.definitionsByCodeAndLayer = index;
    }

    /** Индексирует метаданные под их own-слоем ({@code definition().jurisdiction()}). */
    private static void register(Map<String, Map<JurisdictionLayer, RuleDefinition>> index,
                                 RuleDefinition def) {
        JurisdictionLayer layer = JurisdictionLayer.valueOf(def.jurisdiction().name());
        Map<JurisdictionLayer, RuleDefinition> byLayer =
                index.computeIfAbsent(def.code(), k -> new EnumMap<>(JurisdictionLayer.class));
        // Первая запись для (code, layer) выигрывает — детерминированно при дублях.
        byLayer.putIfAbsent(layer, def);
    }

    /**
     * Метаданные правила по коду и юрисдикции скана с fallback overlay → baseline; empty, если
     * кода нет в коде (мёртвый факт) либо ни один слой юрисдикции его не объявляет.
     */
    public Optional<RuleDefinition> resolve(String code, ScanJurisdiction jurisdiction) {
        Map<JurisdictionLayer, RuleDefinition> byLayer = definitionsByCodeAndLayer.get(code);
        if (byLayer == null) {
            return Optional.empty();
        }
        for (JurisdictionLayer layer : JurisdictionProfiles.layerPriority(jurisdiction)) {
            RuleDefinition def = byLayer.get(layer);
            if (def != null) {
                return Optional.of(def);
            }
        }
        return Optional.empty();
    }

    /**
     * Включено ли правило для данной юрисдикции. MVP: включено, если есть метаданные хотя бы в одном
     * слое юрисдикции (БД-override отложен).
     */
    public boolean isEnabled(String code, ScanJurisdiction jurisdiction) {
        return resolve(code, jurisdiction).isPresent();
    }
}
