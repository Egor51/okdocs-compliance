package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuPatternsTest {

    @Test
    void hasCookieBannerByRegexInText() {
        PageAnalysisResult page = TestFixtures.page("u", "Мы используем cookie на сайте", false,
                List.of(), List.of(), List.of(), "<html></html>");
        assertThat(RuPatterns.hasCookieBanner(TestFixtures.ctx(page))).isTrue();
    }

    @Test
    void hasCookieBannerByCrawlerFlag() {
        PageAnalysisResult page = TestFixtures.page("u", "обычный текст", true,
                List.of(), List.of(), List.of(), "<html></html>");
        assertThat(RuPatterns.hasCookieBanner(TestFixtures.ctx(page))).isTrue();
    }

    @Test
    void parseInnFindsTenDigitInn() {
        PageAnalysisResult page = TestFixtures.page("u",
                "ООО Пример, ИНН 7701234567, г. Москва", false,
                List.of(), List.of(), List.of(), "<html/>");
        assertThat(RuPatterns.parseInn(TestFixtures.ctx(page))).contains("7701234567");
    }

    @Test
    void hasOperatorInfoDetectsOgrn() {
        PageAnalysisResult page = TestFixtures.page("u", "ОГРН 1027700132195", false,
                List.of(), List.of(), List.of(), "<html/>");
        assertThat(RuPatterns.hasOperatorInfo(TestFixtures.ctx(page))).isTrue();
    }

    @Test
    void hasPolicyLinkByInternalLink() {
        PageAnalysisResult page = TestFixtures.page("u", "текст", false,
                List.of(), List.of("/privacy-policy"), List.of(), "<html/>");
        assertThat(RuPatterns.hasPolicyLink(TestFixtures.ctx(page))).isTrue();
    }

    @Test
    void trackersMentionedInPolicyDetectsByUrl() {
        PageAnalysisResult policy = TestFixtures.page("https://site.ru/privacy",
                "мы используем yandex метрику для аналитики", false,
                List.of(), List.of(), List.of(), "<html/>");
        assertThat(RuPatterns.trackersMentionedInPolicy(
                TestFixtures.ctx(policy), Set.of("mc.yandex.ru"))).isTrue();
    }
}
