package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleSupportTest {

    @Test
    void hasDataFormsReadsPdFieldFlag() {
        ScanAnalysisContext ctx = TestFixtures.ctx(
                TestFixtures.simplePage("u", TestFixtures.dataFormNoConsent("/a")));
        assertThat(RuleSupport.hasDataForms(ctx)).isTrue();
    }

    @Test
    void hasDataFormsFalseWhenNoPdField() {
        ScanAnalysisContext ctx = TestFixtures.ctx(
                TestFixtures.simplePage("u", TestFixtures.emptyForm("/a")));
        assertThat(RuleSupport.hasDataForms(ctx)).isFalse();
    }

    @Test
    void hasCookieBannerFlagByCrawlerFlag() {
        PageAnalysisResult page = TestFixtures.page("u", "текст", true,
                List.of(), List.of(), List.of(), "<html></html>");
        assertThat(RuleSupport.hasCookieBannerFlag(TestFixtures.ctx(page))).isTrue();
    }

    @Test
    void externalScriptsDedupesAcrossPages() {
        ScanAnalysisContext ctx = TestFixtures.ctx(
                TestFixtures.pageWithScripts("u1", List.of("googletagmanager.com", "mc.yandex.ru")),
                TestFixtures.pageWithScripts("u2", List.of("mc.yandex.ru")));

        assertThat(RuleSupport.externalScripts(ctx))
                .containsExactly("googletagmanager.com", "mc.yandex.ru");
    }

    @Test
    void domainMatchesExactAndSubdomainNotSuffixTrick() {
        assertThat(RuleSupport.domainMatches("mc.yandex.ru", "mc.yandex.ru")).isTrue();
        assertThat(RuleSupport.domainMatches("a.b.google-analytics.com", "google-analytics.com")).isTrue();
        assertThat(RuleSupport.domainMatches("evil-google-analytics.com", "google-analytics.com")).isFalse();
    }
}
