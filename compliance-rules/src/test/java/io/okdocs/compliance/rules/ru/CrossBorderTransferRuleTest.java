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

class CrossBorderTransferRuleTest {

    private final CrossBorderTransferRule rule = new CrossBorderTransferRule();

    @Test
    void definitionMatchesPlan() {
        assertThat(rule.definition().code()).isEqualTo("POSSIBLE_CROSS_BORDER_TRANSFER");
        assertThat(rule.definition().severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(rule.definition().category()).isEqualTo(FindingCategory.HOSTING);
    }

    @Test
    void flagsForeignServiceUnverified() {
        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(
                TestFixtures.pageWithScripts("https://site.ru", List.of("google-analytics.com"))));

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
            assertThat(f.confidence()).isEqualTo(0.85);
        });
    }

    @Test
    void perPageMentionDoesNotLeakToOtherDomains() {
        // Регрессия на «липкий» inPolicy: google упомянут в политике (DETECTED), hubspot на другой
        // странице — нет, должен остаться UNVERIFIED.
        PageAnalysisResult googlePage = TestFixtures.pageWithScripts(
                "https://site.ru/a", List.of("google-analytics.com"));
        PageAnalysisResult hubspotPage = TestFixtures.pageWithScripts(
                "https://site.ru/b", List.of("hubspot.com"));
        PageAnalysisResult policy = TestFixtures.page("https://site.ru/privacy",
                "используем google analytics", false, List.of(), List.of(), List.of(), "<html/>");

        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(googlePage, hubspotPage, policy));

        RuleFact google = facts.stream().filter(f -> f.matchedSignals().contains("google")).findFirst().orElseThrow();
        RuleFact hubspot = facts.stream().filter(f -> f.matchedSignals().contains("hubspot")).findFirst().orElseThrow();
        assertThat(google.verificationStatus()).isEqualTo(VerificationStatus.DETECTED);
        assertThat(hubspot.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
    }

    @Test
    void ignoresRussianTrackers() {
        // Российские сервисы — не трансграничная передача, проверяются в ThirdPartyTrackersRule.
        assertThat(rule.evaluate(TestFixtures.ctx(
                TestFixtures.pageWithScripts("https://site.ru", List.of("mc.yandex.ru"))))).isEmpty();
    }
}
