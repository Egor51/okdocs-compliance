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
    void remainsUnverifiedWhenCookieBannerPresentButNoPreConsentObserved() {
        PageAnalysisResult page = TestFixtures.page("https://site.ru", "текст", true,
                List.of(), List.of(), List.of("mc.yandex.ru"), "<html></html>");

        assertThat(rule.evaluate(TestFixtures.ctx(page))).singleElement().satisfies(f -> {
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
            assertThat(f.confidence()).isEqualTo(0.60);
        });
    }

    @Test
    void silentWhenNoTrackers() {
        assertThat(rule.evaluate(TestFixtures.ctx(
                TestFixtures.pageWithScripts("https://site.ru", List.of("cdn.example.com"))))).isEmpty();
    }

    @Test
    void confirmsWhenTrackerRequestObservedBeforeConsent() {
        // DYNAMIC: CDP-таймлайн зафиксировал запрос трекера до баннера → CONFIRMED 0.95.
        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(
                TestFixtures.dynamicPageWithPreConsent("https://site.ru",
                        List.of("mc.yandex.ru"), List.of("mc.yandex.ru"))));

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.CONFIRMED);
            assertThat(f.confidence()).isEqualTo(0.95);
            assertThat(f.evidence()).contains("mc.yandex.ru");
        });
    }

    @Test
    void confirmsWhenCookieBannerPresentButTrackerRequestObservedBeforeIt() {
        // Итоговый DOM уже содержит баннер, но CDP-таймлайн доказал, что трекер ушёл раньше.
        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(
                TestFixtures.dynamicPageWithPreConsent("https://site.ru",
                        List.of("mc.yandex.ru"), List.of("mc.yandex.ru"), true)));

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.CONFIRMED);
            assertThat(f.confidence()).isEqualTo(0.95);
        });
    }

    @Test
    void downgradesWhenDynamicButNoPreConsentObserved() {
        // DYNAMIC, трекер есть, но запрос ДО согласия не зафиксирован (preConsent пуст) →
        // честный UNVERIFIED 0.60, а не ложный 0.95.
        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(
                TestFixtures.dynamicPageWithPreConsent("https://site.ru",
                        List.of("mc.yandex.ru"), List.of())));

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
            assertThat(f.confidence()).isEqualTo(0.60);
        });
    }

    @Test
    void preConsentHostMatchesTrackerBySubdomain() {
        // Наблюдённый хост — поддомен/конкретный хост, в справочнике запись по корню: matchTracker
        // должен свести его к трекеру и дать CONFIRMED.
        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(
                TestFixtures.dynamicPageWithPreConsent("https://site.ru",
                        List.of("www.google-analytics.com"),
                        List.of("www.google-analytics.com"))));

        assertThat(facts).singleElement().satisfies(f ->
                assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.CONFIRMED));
    }
}
