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
    void detectsKnownTrackerWhenNotDisclosedInPolicy() {
        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(
                TestFixtures.pageWithScripts("https://site.ru", List.of("mc.yandex.ru"))));

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("THIRD_PARTY_TRACKERS");
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.DETECTED);
            assertThat(f.confidence()).isEqualTo(0.85);
            assertThat(f.matchedSignals()).contains("mc.yandex.ru");
        });
    }

    @Test
    void silentWhenMentionedInPolicy() {
        PageAnalysisResult tracked = TestFixtures.pageWithScripts(
                "https://site.ru", List.of("mc.yandex.ru"));
        PageAnalysisResult policy = TestFixtures.page("https://site.ru/privacy",
                "используем yandex метрику", false, List.of(), List.of(), List.of(), "<html/>");

        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(tracked, policy));

        assertThat(facts).isEmpty();
    }

    @Test
    void yandexMapsFooterInPolicyDoesNotCountAsTrackerDisclosure() {
        PageAnalysisResult tracked = TestFixtures.pageWithScripts(
                "https://site.ru", List.of("mc.yandex.ru"));
        PageAnalysisResult policy = TestFixtures.page("https://site.ru/privacy",
                "Политика обработки персональных данных. Мы на картах: yandex.ru.",
                false, List.of(), List.of(), List.of(), "<html/>");

        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(tracked, policy));

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.DETECTED);
            assertThat(f.confidence()).isEqualTo(0.85);
        });
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

        RuleFact hubspot = facts.stream().filter(f -> f.matchedSignals().contains("hubspot")).findFirst().orElseThrow();
        assertThat(facts).hasSize(1); // раскрытый Yandex не создаёт finding; HubSpot остаётся.
        assertThat(hubspot.verificationStatus()).isEqualTo(VerificationStatus.DETECTED);
    }

    @Test
    void disclosureOfOneProviderDoesNotHideUndisclosedProviderOnSamePage() {
        PageAnalysisResult tracked = TestFixtures.pageWithScripts(
                "https://site.ru", List.of("mc.yandex.ru", "hubspot.com"));
        PageAnalysisResult policy = TestFixtures.page("https://site.ru/privacy",
                "Для аналитики используем Яндекс Метрику и cookie.", false,
                List.of(), List.of(), List.of(), "<html/>");

        assertThat(rule.evaluate(TestFixtures.ctx(tracked, policy))).singleElement().satisfies(f -> {
            assertThat(f.matchedSignals()).contains("hubspot.com");
            assertThat(f.matchedSignals()).doesNotContain("mc.yandex.ru");
        });
    }

    @Test
    void silentWhenNoTrackerDomains() {
        assertThat(rule.evaluate(TestFixtures.ctx(
                TestFixtures.pageWithScripts("https://site.ru", List.of("cdn.example.com"))))).isEmpty();
    }
}
