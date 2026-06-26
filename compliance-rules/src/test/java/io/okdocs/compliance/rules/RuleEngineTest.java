package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.rules.ru.UnprotectedDataFormsRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    @Test
    void aggregatesFactsFromAllRules() {
        RuleEngine engine = new RuleEngine(List.of(new UnprotectedDataFormsRule(), new OkRule()));
        ScanAnalysisContext ctx = TestFixtures.ctx(
                TestFixtures.simplePage("https://site.ru", TestFixtures.dataFormNoConsent("/lead")));

        RuleEngineResult result = engine.evaluate(ctx);

        assertThat(result.errors()).isEmpty();
        assertThat(result.facts())
                .extracting(RuleFact::code)
                .containsExactlyInAnyOrder("UNPROTECTED_DATA_FORMS", "OK");
        assertThat(result.outcomes())
                .extracting(RuleOutcome::status)
                .contains(RuleOutcomeStatus.FAILED);
    }

    @Test
    void isolatesFailingRuleAndKeepsOthers() {
        RuleEngine engine = new RuleEngine(List.of(new ThrowingRule(), new OkRule()));

        RuleEngineResult result = engine.evaluate(TestFixtures.ctx());

        assertThat(result.facts()).extracting(RuleFact::code).containsExactly("OK");
        assertThat(result.errors()).singleElement().satisfies(err -> {
            assertThat(err.ruleCode()).isEqualTo("BOOM");
            assertThat(err.exceptionType()).isEqualTo("IllegalStateException");
            assertThat(err.message()).isEqualTo("kaboom");
        });
        assertThat(result.outcomes())
                .filteredOn(o -> o.code().equals("BOOM"))
                .singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(RuleOutcomeStatus.NOT_EVALUATED));
    }

    @Test
    void survivesRuleWhoseDefinitionThrows() {
        // P3: ломается definition(), не evaluate — движок не должен падать, ошибка под именем класса.
        RuleEngine engine = new RuleEngine(List.of(new BrokenDefinitionRule(), new OkRule()));

        RuleEngineResult result = engine.evaluate(TestFixtures.ctx());

        assertThat(result.facts()).extracting(RuleFact::code).containsExactly("OK");
        assertThat(result.errors()).singleElement()
                .satisfies(err -> assertThat(err.ruleCode()).isEqualTo("BrokenDefinitionRule"));
        assertThat(result.outcomes())
                .filteredOn(o -> o.code().equals("BrokenDefinitionRule"))
                .singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(RuleOutcomeStatus.NOT_EVALUATED));
    }

    @Test
    void runsOnlyRulesMatchingScanJurisdiction() {
        // RU-скан: EU-правило (GDPR) пропускается молча, без ошибки — не его юрисдикция.
        RuleEngine engine = new RuleEngine(List.of(new OkRule(), new EuRule()));

        RuleEngineResult result = engine.evaluate(TestFixtures.ctx()); // ctx() == RU

        assertThat(result.facts()).extracting(RuleFact::code).containsExactly("OK");
        assertThat(result.errors()).isEmpty();
        assertThat(result.outcomes()).extracting(RuleOutcome::code).doesNotContain("EU_ONLY");
    }

    @Test
    void commonEuRuleRunsOnDeScanViaLayerInheritance() {
        // DE-скан активирует слои {EU, DE}; common EU-правило ({EU}) пересекается → запускается.
        RuleEngine engine = new RuleEngine(List.of(new CommonEuRule()));

        RuleEngineResult result = engine.evaluate(TestFixtures.ctxFor(ScanJurisdiction.DE));

        assertThat(result.facts()).extracting(RuleFact::code).containsExactly("EU_COMMON");
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void commonEuRuleDoesNotRunOnUkScan() {
        // UK не наследует EU baseline (слои {UK}); EU-правило молча пропускается.
        RuleEngine engine = new RuleEngine(List.of(new CommonEuRule()));

        RuleEngineResult result = engine.evaluate(TestFixtures.ctxFor(ScanJurisdiction.UK));

        assertThat(result.facts()).isEmpty();
        assertThat(result.errors()).isEmpty();
        assertThat(result.outcomes()).extracting(RuleOutcome::code).doesNotContain("EU_COMMON");
    }

    @Test
    void ruRuleDoesNotRunOnEuScan() {
        // RU-правило (дефолтный слой {RU}) не пересекается со слоями EU-скана.
        RuleEngine engine = new RuleEngine(List.of(new OkRule()));

        RuleEngineResult result = engine.evaluate(TestFixtures.ctxFor(ScanJurisdiction.EU));

        assertThat(result.facts()).isEmpty();
        assertThat(result.outcomes()).extracting(RuleOutcome::code).doesNotContain("OK");
    }

    @Test
    void commonTechnicalRuleRunsAcrossRuEuDeUkScans() {
        // MISSING_HSTS — common-детектор (supportedLayers={RU,EU,UK}). Запускается на RU/EU/UK и на
        // DE (наследует слой EU): отсутствие HSTS на HTTPS-ответе даёт факт во всех четырёх.
        io.okdocs.compliance.rules.common.MissingHstsRule rule =
                new io.okdocs.compliance.rules.common.MissingHstsRule();
        for (ScanJurisdiction j : List.of(ScanJurisdiction.RU, ScanJurisdiction.EU,
                ScanJurisdiction.DE, ScanJurisdiction.UK)) {
            RuleEngineResult result = new RuleEngine(List.of(rule)).evaluate(
                    TestFixtures.ctxForWithResponses(j,
                            TestFixtures.response("https://site.ru/", java.util.Map.of())));
            assertThat(result.facts())
                    .as("MISSING_HSTS on %s scan", j)
                    .extracting(RuleFact::code)
                    .containsExactly("MISSING_HSTS");
        }
    }

    @Test
    void emptyRuleSetYieldsEmptyResult() {
        RuleEngineResult result = new RuleEngine(List.of()).evaluate(TestFixtures.ctx());
        assertThat(result.facts()).isEmpty();
        assertThat(result.errors()).isEmpty();
        assertThat(result.outcomes()).isEmpty();
    }

    @Test
    void emptyFactsWithPagesYieldsPassedOutcome() {
        RuleEngine engine = new RuleEngine(List.of(new PassingRule()));

        RuleEngineResult result = engine.evaluate(TestFixtures.ctx(TestFixtures.simplePage("https://site.ru")));

        assertThat(result.facts()).isEmpty();
        assertThat(result.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.code()).isEqualTo("PASS");
            assertThat(o.status()).isEqualTo(RuleOutcomeStatus.PASSED);
            assertThat(o.positiveTitle()).isEqualTo("pass positive");
            assertThat(o.positiveMessage()).isEqualTo("pass positive message");
        });
    }

    @Test
    void emptyFactsWithoutPagesYieldsNotEvaluatedOutcome() {
        RuleEngine engine = new RuleEngine(List.of(new PassingRule()));

        RuleEngineResult result = engine.evaluate(TestFixtures.ctx());

        assertThat(result.facts()).isEmpty();
        assertThat(result.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.code()).isEqualTo("PASS");
            assertThat(o.status()).isEqualTo(RuleOutcomeStatus.NOT_EVALUATED);
        });
    }

    @Test
    void notApplicableRuleYieldsNotEvaluatedNotPassed() {
        // Страницы есть, но правилу нечего анализировать (appliesTo=false) → NOT_EVALUATED, НЕ PASSED:
        // «не проверяли» ≠ «нарушений нет». Регрессия на ложный positive (cookie-правило без cookies).
        RuleEngine engine = new RuleEngine(List.of(new NotApplicableRule()));

        RuleEngineResult result = engine.evaluate(TestFixtures.ctx(TestFixtures.simplePage("https://site.ru")));

        assertThat(result.facts()).isEmpty();
        assertThat(result.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.code()).isEqualTo("NA");
            assertThat(o.status()).isEqualTo(RuleOutcomeStatus.NOT_EVALUATED);
        });
    }

    private static final class NotApplicableRule implements Rule {
        @Override
        public RuleDefinition definition() {
            return new RuleDefinition("NA", ScanJurisdiction.RU, FindingSeverity.LOW,
                    FindingCategory.OTHER, "na", null, null, null, null, "na positive", "na message");
        }

        @Override
        public boolean appliesTo(ScanAnalysisContext ctx) {
            return false;
        }

        @Override
        public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
            return List.of();
        }
    }

    private static final class OkRule implements Rule {
        @Override
        public RuleDefinition definition() {
            return new RuleDefinition("OK", ScanJurisdiction.RU, FindingSeverity.LOW,
                    FindingCategory.OTHER, "ok", null, null, null, null);
        }

        @Override
        public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
            return List.of(new RuleFact("OK", null, null, null, null, null, null, null));
        }
    }

    /** EU-правило (GDPR): на RU-скане {@link RuleEngine} его пропускает. */
    private static final class EuRule implements Rule {
        @Override
        public RuleDefinition definition() {
            return new RuleDefinition("EU_ONLY", ScanJurisdiction.EU, FindingSeverity.LOW,
                    FindingCategory.OTHER, "eu", null, null, null, null);
        }

        @Override
        public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
            return List.of(new RuleFact("EU_ONLY", null, null, null, null, null, null, null));
        }
    }

    /** Common EU-правило: поддерживает слой EU, поэтому работает на сканах EU/DE/FR/ES. */
    private static final class CommonEuRule implements Rule {
        @Override
        public RuleDefinition definition() {
            return new RuleDefinition("EU_COMMON", ScanJurisdiction.EU, FindingSeverity.LOW,
                    FindingCategory.OTHER, "eu common", null, null, null, null);
        }

        @Override
        public java.util.Set<io.okdocs.compliance.contracts.enums.JurisdictionLayer> supportedLayers() {
            return java.util.Set.of(io.okdocs.compliance.contracts.enums.JurisdictionLayer.EU);
        }

        @Override
        public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
            return List.of(new RuleFact("EU_COMMON", null, null, null, null, null, null, null));
        }
    }

    private static final class BrokenDefinitionRule implements Rule {
        @Override
        public RuleDefinition definition() {
            throw new IllegalStateException("definition is broken");
        }

        @Override
        public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
            throw new IllegalStateException("evaluate also throws");
        }
    }

    private static final class ThrowingRule implements Rule {
        @Override
        public RuleDefinition definition() {
            return new RuleDefinition("BOOM", ScanJurisdiction.RU, FindingSeverity.LOW,
                    FindingCategory.OTHER, "boom", null, null, null, null);
        }

        @Override
        public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
            throw new IllegalStateException("kaboom");
        }
    }

    private static final class PassingRule implements Rule {
        @Override
        public RuleDefinition definition() {
            return new RuleDefinition("PASS", ScanJurisdiction.RU, FindingSeverity.LOW,
                    FindingCategory.OTHER, "pass", null, null, null, null,
                    "pass positive", "pass positive message");
        }

        @Override
        public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
            return List.of();
        }
    }
}
