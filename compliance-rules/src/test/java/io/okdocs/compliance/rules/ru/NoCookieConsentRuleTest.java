package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoCookieConsentRuleTest {

    private final NoCookieConsentRule rule = new NoCookieConsentRule();

    private static final String TRACKER_HTML =
            "<script src='https://mc.yandex.ru/metrika/tag.js'></script>";

    @Test
    void isMedium() {
        assertThat(rule.definition().severity()).isEqualTo(FindingSeverity.MEDIUM);
    }

    @Test
    void flagsWhenTrackerPresentButNoBanner() {
        PageAnalysisResult page = TestFixtures.page("https://site.ru", "текст", false,
                List.of(), List.of(), List.of(), TRACKER_HTML);

        assertThat(rule.evaluate(TestFixtures.ctx(page))).singleElement()
                .satisfies(f -> assertThat(f.code()).isEqualTo("NO_COOKIE_CONSENT"));
    }

    @Test
    void flagsWhenTrackerKnownByDomainEvenIfHtmlEmpty() {
        // P1: html урезан/пуст, но краулер дал домен трекера — правило всё равно срабатывает.
        PageAnalysisResult page = TestFixtures.page("https://site.ru", "текст", false,
                List.of(), List.of(), List.of("mc.yandex.ru"), "");

        assertThat(rule.evaluate(TestFixtures.ctx(page))).singleElement()
                .satisfies(f -> assertThat(f.code()).isEqualTo("NO_COOKIE_CONSENT"));
    }

    @Test
    void silentWhenNoTracker() {
        PageAnalysisResult page = TestFixtures.page("https://site.ru", "текст", false,
                List.of(), List.of(), List.of(), "<html></html>");

        assertThat(rule.evaluate(TestFixtures.ctx(page))).isEmpty();
    }

    @Test
    void silentWhenCookieBannerPresent() {
        PageAnalysisResult page = TestFixtures.page("https://site.ru", "текст", true,
                List.of(), List.of(), List.of(), TRACKER_HTML);

        assertThat(rule.evaluate(TestFixtures.ctx(page))).isEmpty();
    }

    @Test
    void silentWhenNoPages() {
        assertThat(rule.evaluate(TestFixtures.ctx())).isEmpty();
    }
}
