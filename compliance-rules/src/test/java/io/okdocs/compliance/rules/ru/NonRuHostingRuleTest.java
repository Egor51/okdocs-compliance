package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.enums.RegistryStatus;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NonRuHostingRuleTest {

    private final NonRuHostingRule rule = new NonRuHostingRule();

    @Test
    void silentWhenHostInRu() {
        assertThat(rule.evaluate(TestFixtures.ctx("RU", RegistryStatus.FOUND,
                TestFixtures.simplePage("https://site.ru")))).isEmpty();
    }

    @Test
    void flagsForeignHostDetected() {
        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx("DE", RegistryStatus.FOUND,
                TestFixtures.simplePage("https://site.ru")));

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("HOSTING_OUTSIDE_RU_DETECTED");
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.DETECTED);
            assertThat(f.confidence()).isEqualTo(0.85);
            assertThat(f.matchedSignals()).contains("DE");
        });
    }

    @Test
    void unverifiedWhenCountryUnknown() {
        // GeoIP не разрезолвил — честное «не удалось проверить», а не молчание (PLAN.md §1.6).
        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(null, RegistryStatus.FOUND,
                TestFixtures.simplePage("https://site.ru")));

        assertThat(facts).singleElement()
                .satisfies(f -> assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED));
    }
}
