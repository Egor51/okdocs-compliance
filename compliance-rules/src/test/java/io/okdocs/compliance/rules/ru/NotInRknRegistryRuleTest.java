package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.RegistryStatus;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotInRknRegistryRuleTest {

    private final NotInRknRegistryRule rule = new NotInRknRegistryRule();

    private ScanAnalysisContext withRegistry(RegistryStatus status) {
        return TestFixtures.ctx("RU", status, TestFixtures.simplePage("https://site.ru"));
    }

    @Test
    void notFoundYieldsConfirmedFinding() {
        List<RuleFact> facts = rule.evaluate(withRegistry(RegistryStatus.NOT_FOUND));
        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("RKN_REGISTRY_NOT_VERIFIED");
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.CONFIRMED);
        });
    }

    @Test
    void lookupFailedYieldsUnverifiedFinding() {
        assertThat(rule.evaluate(withRegistry(RegistryStatus.LOOKUP_FAILED)))
                .singleElement()
                .satisfies(f -> assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED));
    }

    @Test
    void foundYieldsNoFinding() {
        assertThat(rule.evaluate(withRegistry(RegistryStatus.FOUND))).isEmpty();
    }

    @Test
    void nullRegistryYieldsNoFinding() {
        assertThat(rule.evaluate(withRegistry(null))).isEmpty();
    }

    @Test
    void includesInnInEvidenceWhenAvailable() {
        ScanAnalysisContext ctx = TestFixtures.ctx("RU", RegistryStatus.NOT_FOUND,
                TestFixtures.page("https://site.ru", "ИНН 7701234567", false,
                        List.of(), List.of(), List.of(), "<html/>"));

        assertThat(rule.evaluate(ctx).get(0).matchedSignals()).contains("7701234567");
    }
}
