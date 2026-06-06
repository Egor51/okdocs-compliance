package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThirdPartyTrackersRuleTest {

    private final ThirdPartyTrackersRule rule = new ThirdPartyTrackersRule();

    @Test
    void flagsKnownTrackerUnverifiedWhenNotInPolicy() {
        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(
                TestFixtures.pageWithScripts("https://site.ru", List.of("mc.yandex.ru"))));

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("THIRD_PARTY_TRACKERS");
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
            assertThat(f.confidence()).isEqualTo(0.90);
            assertThat(f.matchedSignals()).contains("mc.yandex.ru");
        });
    }

    @Test
    void detectedWhenMentionedInPolicy() {
        PageAnalysisResult tracked = TestFixtures.pageWithScripts(
                "https://site.ru", List.of("mc.yandex.ru"));
        PageAnalysisResult policy = TestFixtures.page("https://site.ru/privacy",
                "используем yandex метрику", false, List.of(), List.of(), List.of(), "<html/>");

        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(tracked, policy));

        assertThat(facts).isNotEmpty();
        assertThat(facts.get(0).verificationStatus()).isEqualTo(VerificationStatus.DETECTED);
        assertThat(facts.get(0).confidence()).isEqualTo(0.70);
    }

    @Test
    void perPageMentionDoesNotLeakToOtherDomains() {
        // Регрессия на «липкий» inPolicy: yandex упомянут в политике (DETECTED), а hubspot на
        // другой странице — нет, и должен остаться UNVERIFIED, а не унаследовать DETECTED.
        PageAnalysisResult yandexPage = TestFixtures.pageWithScripts(
                "https://site.ru/a", List.of("mc.yandex.ru"));
        PageAnalysisResult hubspotPage = TestFixtures.pageWithScripts(
                "https://site.ru/b", List.of("hubspot.com"));
        PageAnalysisResult policy = TestFixtures.page("https://site.ru/privacy",
                "используем yandex метрику", false, List.of(), List.of(), List.of(), "<html/>");

        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(yandexPage, hubspotPage, policy));

        RuleFact yandex = facts.stream().filter(f -> f.matchedSignals().contains("yandex")).findFirst().orElseThrow();
        RuleFact hubspot = facts.stream().filter(f -> f.matchedSignals().contains("hubspot")).findFirst().orElseThrow();
        assertThat(yandex.verificationStatus()).isEqualTo(VerificationStatus.DETECTED);
        assertThat(hubspot.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
    }

    @Test
    void silentWhenNoTrackerDomains() {
        assertThat(rule.evaluate(TestFixtures.ctx(
                TestFixtures.pageWithScripts("https://site.ru", List.of("cdn.example.com"))))).isEmpty();
    }
}
