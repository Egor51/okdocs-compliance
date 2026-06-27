package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.JurisdictionLayer;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuleMetadataResolverTest {

    private static final String CODE = "TRACKERS_BEFORE_CONSENT";

    /** Правило с заданным набором слоёв и метаданными (legalBasis отличает слои в проверках). */
    private static Rule rule(String legalBasis, ScanJurisdiction defJurisdiction,
                             Set<JurisdictionLayer> layers) {
        return new Rule() {
            @Override
            public RuleDefinition definition() {
                return new RuleDefinition(CODE, defJurisdiction, FindingSeverity.HIGH,
                        FindingCategory.COOKIES, "title", null, legalBasis, null, null);
            }

            @Override
            public Set<JurisdictionLayer> supportedLayers() {
                return layers;
            }

            @Override
            public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
                return List.of();
            }
        };
    }

    @Test
    void deOverlayMetadataOverridesEuBaseline() {
        // EU common-правило ({EU}) + DE overlay ({DE}) под одним кодом. DE-скан берёт DE-метаданные.
        RuleMetadataResolver resolver = new RuleMetadataResolver(List.of(
                rule("GDPR Art. 6 / ePrivacy", ScanJurisdiction.EU, Set.of(JurisdictionLayer.EU)),
                rule("TDDDG § 25", ScanJurisdiction.DE, Set.of(JurisdictionLayer.DE))));

        assertThat(resolver.resolve(CODE, ScanJurisdiction.DE))
                .get()
                .extracting(RuleDefinition::legalBasis)
                .isEqualTo("TDDDG § 25");
    }

    @Test
    void deFallsBackToEuBaselineWhenNoOverlay() {
        // Только EU common-правило, DE-специфичного нет. DE-скан падает на EU-baseline.
        RuleMetadataResolver resolver = new RuleMetadataResolver(List.of(
                rule("GDPR Art. 6 / ePrivacy", ScanJurisdiction.EU, Set.of(JurisdictionLayer.EU))));

        assertThat(resolver.resolve(CODE, ScanJurisdiction.DE))
                .get()
                .extracting(RuleDefinition::legalBasis)
                .isEqualTo("GDPR Art. 6 / ePrivacy");
    }

    @Test
    void ukUsesUkMetadataOnlyNotEuBaseline() {
        // UK не наследует EU baseline: EU-правило для UK-скана недоступно, только UK-метаданные.
        RuleMetadataResolver resolver = new RuleMetadataResolver(List.of(
                rule("GDPR Art. 6 / ePrivacy", ScanJurisdiction.EU, Set.of(JurisdictionLayer.EU)),
                rule("PECR reg. 6", ScanJurisdiction.UK, Set.of(JurisdictionLayer.UK))));

        assertThat(resolver.resolve(CODE, ScanJurisdiction.UK))
                .get()
                .extracting(RuleDefinition::legalBasis)
                .isEqualTo("PECR reg. 6");
    }

    @Test
    void ukWithoutUkRuleResolvesEmptyDespiteEuRulePresent() {
        // Только EU-правило; UK-скан не должен подхватить EU-метаданные (нет наследования).
        RuleMetadataResolver resolver = new RuleMetadataResolver(List.of(
                rule("GDPR Art. 6 / ePrivacy", ScanJurisdiction.EU, Set.of(JurisdictionLayer.EU))));

        assertThat(resolver.resolve(CODE, ScanJurisdiction.UK)).isEmpty();
    }

    @Test
    void commonCodeResolvesRuOwnMetadataAndEuOverlay() {
        // Реальный common-детектор (own-метаданные RU) + EU-overlay из EuCommonMetadata.
        RuleMetadataResolver resolver = new RuleMetadataResolver(
                List.of(new io.okdocs.compliance.rules.common.MissingHstsRule()),
                io.okdocs.compliance.rules.eu.EuCommonMetadata.entries());

        // RU-скан → own-метаданные правила (152-ФЗ).
        assertThat(resolver.resolve("MISSING_HSTS", ScanJurisdiction.RU))
                .get().extracting(RuleDefinition::legalBasis)
                .matches(s -> s.contains("152-ФЗ"), "RU legal basis");
        // EU-скан → EU-overlay (GDPR Art. 32), не русский текст.
        assertThat(resolver.resolve("MISSING_HSTS", ScanJurisdiction.EU))
                .get().extracting(RuleDefinition::legalBasis)
                .isEqualTo("GDPR Art. 32 (security of processing)");
        // DE-скан наследует слой EU → тоже GDPR-overlay.
        assertThat(resolver.resolve("MISSING_HSTS", ScanJurisdiction.DE))
                .get().extracting(RuleDefinition::legalBasis)
                .isEqualTo("GDPR Art. 32 (security of processing)");
        // UK не наследует EU и UK-метаданных пока нет → пусто (находка отбросится). Фаза 6 добавит UK.
        assertThat(resolver.resolve("MISSING_HSTS", ScanJurisdiction.UK)).isEmpty();
    }

    @Test
    void commonCodeResolvesUkMetadataNotEuOnUkScan() {
        // UK не наследует EU: на UK-скане common-код берёт UK-overlay (PECR/UK GDPR), не EU.
        RuleMetadataResolver resolver = new RuleMetadataResolver(
                List.of(new io.okdocs.compliance.rules.common.SessionCookieWithoutHttpOnlyRule()),
                concat(io.okdocs.compliance.rules.eu.EuCommonMetadata.entries(),
                        io.okdocs.compliance.rules.uk.UkCommonMetadata.entries()));

        assertThat(resolver.resolve("SESSION_COOKIE_WITHOUT_HTTPONLY", ScanJurisdiction.UK))
                .get().extracting(RuleDefinition::legalBasis)
                .matches(s -> s.contains("PECR") || s.contains("UK GDPR"), "UK legal basis");
        // На EU-скане тот же код — EU-overlay (ePrivacy/GDPR).
        assertThat(resolver.resolve("SESSION_COOKIE_WITHOUT_HTTPONLY", ScanJurisdiction.EU))
                .get().extracting(RuleDefinition::legalBasis)
                .matches(s -> s.contains("ePrivacy") || s.contains("GDPR"), "EU legal basis");
    }

    private static List<RuleDefinition> concat(List<RuleDefinition> a, List<RuleDefinition> b) {
        List<RuleDefinition> all = new java.util.ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    @Test
    void unknownCodeResolvesEmpty() {
        RuleMetadataResolver resolver = new RuleMetadataResolver(List.of(
                rule("GDPR Art. 6 / ePrivacy", ScanJurisdiction.EU, Set.of(JurisdictionLayer.EU))));

        assertThat(resolver.resolve("GHOST", ScanJurisdiction.EU)).isEmpty();
    }

    @Test
    void ruRuleUsesDefaultSingleLayer() {
        // RU-правило без переопределения supportedLayers() — дефолтный слой {RU} из definition().
        RuleMetadataResolver resolver = new RuleMetadataResolver(List.of(
                new Rule() {
                    @Override
                    public RuleDefinition definition() {
                        return new RuleDefinition("NO_PRIVACY_POLICY", ScanJurisdiction.RU,
                                FindingSeverity.HIGH, FindingCategory.DOCUMENTS, "t", null,
                                "ст. 18.1 152-ФЗ", null, null);
                    }

                    @Override
                    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
                        return List.of();
                    }
                }));

        assertThat(resolver.resolve("NO_PRIVACY_POLICY", ScanJurisdiction.RU))
                .get().extracting(RuleDefinition::legalBasis).isEqualTo("ст. 18.1 152-ФЗ");
        assertThat(resolver.resolve("NO_PRIVACY_POLICY", ScanJurisdiction.EU)).isEmpty();
    }
}
