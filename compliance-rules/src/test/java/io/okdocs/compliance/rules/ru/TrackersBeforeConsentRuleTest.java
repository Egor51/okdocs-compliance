package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackersBeforeConsentRuleTest {

    private final TrackersBeforeConsentRule rule = new TrackersBeforeConsentRule();

    @Test
    void definitionMatchesPlan() {
        assertThat(rule.definition().code()).isEqualTo("POSSIBLE_TRACKERS_BEFORE_CONSENT");
        assertThat(rule.definition().severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(rule.definition().category()).isEqualTo(FindingCategory.TRACKERS);
    }

    @Test
    void flagsUnverifiedOnStaticWhenTrackerAndNoBanner() {
        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(
                TestFixtures.pageWithScripts("https://site.ru", List.of("mc.yandex.ru"))));

        // STATIC: порядок загрузки не наблюдается → вероятностный результат.
        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
            assertThat(f.confidence()).isEqualTo(0.60);
        });
    }

    @Test
    void silentWhenCookieBannerPresent() {
        PageAnalysisResult page = TestFixtures.page("https://site.ru", "текст", true,
                List.of(), List.of(), List.of("mc.yandex.ru"), "<html></html>");

        assertThat(rule.evaluate(TestFixtures.ctx(page))).isEmpty();
    }

    @Test
    void silentWhenNoTrackers() {
        assertThat(rule.evaluate(TestFixtures.ctx(
                TestFixtures.pageWithScripts("https://site.ru", List.of("cdn.example.com"))))).isEmpty();
    }
}
