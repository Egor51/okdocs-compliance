package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentDefaultCheckedRuleTest {

    private final ConsentDefaultCheckedRule rule = new ConsentDefaultCheckedRule();

    @Test
    void flagsDefaultCheckedConsent() {
        ScanAnalysisContext ctx = TestFixtures.ctx(
                TestFixtures.simplePage("https://site.ru", TestFixtures.dataFormDefaultChecked("/lead")));

        List<RuleFact> facts = rule.evaluate(ctx);

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("CONSENT_DEFAULT_CHECKED");
            // Реализуемо строго на STATIC — CONFIRMED.
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.CONFIRMED);
            assertThat(f.confidence()).isEqualTo(0.90);
        });
    }

    @Test
    void silentWhenConsentNotDefaultChecked() {
        ScanAnalysisContext ctx = TestFixtures.ctx(
                TestFixtures.simplePage("https://site.ru", TestFixtures.dataFormWithConsent("/lead")));

        assertThat(rule.evaluate(ctx)).isEmpty();
    }
}
