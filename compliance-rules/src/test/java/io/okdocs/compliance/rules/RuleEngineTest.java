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
    }

    @Test
    void survivesRuleWhoseDefinitionThrows() {
        // P3: ломается definition(), не evaluate — движок не должен падать, ошибка под именем класса.
        RuleEngine engine = new RuleEngine(List.of(new BrokenDefinitionRule(), new OkRule()));

        RuleEngineResult result = engine.evaluate(TestFixtures.ctx());

        assertThat(result.facts()).extracting(RuleFact::code).containsExactly("OK");
        assertThat(result.errors()).singleElement()
                .satisfies(err -> assertThat(err.ruleCode()).isEqualTo("BrokenDefinitionRule"));
    }

    @Test
    void runsOnlyRulesMatchingScanJurisdiction() {
        // RU-скан: EU-правило (GDPR) пропускается молча, без ошибки — не его юрисдикция.
        RuleEngine engine = new RuleEngine(List.of(new OkRule(), new EuRule()));

        RuleEngineResult result = engine.evaluate(TestFixtures.ctx()); // ctx() == RU

        assertThat(result.facts()).extracting(RuleFact::code).containsExactly("OK");
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void emptyRuleSetYieldsEmptyResult() {
        RuleEngineResult result = new RuleEngine(List.of()).evaluate(TestFixtures.ctx());
        assertThat(result.facts()).isEmpty();
        assertThat(result.errors()).isEmpty();
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
}
