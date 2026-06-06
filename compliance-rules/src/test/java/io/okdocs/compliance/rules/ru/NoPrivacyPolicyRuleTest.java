package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoPrivacyPolicyRuleTest {

    private final NoPrivacyPolicyRule rule = new NoPrivacyPolicyRule();

    @Test
    void flagsWhenNoPolicyLinkOrText() {
        ScanAnalysisContext ctx = TestFixtures.ctx(
                TestFixtures.simplePage("https://site.ru", TestFixtures.dataFormNoConsent("/subscribe")));

        List<RuleFact> facts = rule.evaluate(ctx);

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("NO_PRIVACY_POLICY");
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        });
    }

    @Test
    void silentWhenPolicyLinkInInternalLinks() {
        PageAnalysisResult page = TestFixtures.page("https://site.ru", "текст", false,
                List.of(), List.of("/privacy-policy"), List.of(), "<html></html>");

        assertThat(rule.evaluate(TestFixtures.ctx(page))).isEmpty();
    }

    @Test
    void silentWhenPolicyMentionedInText() {
        PageAnalysisResult page = TestFixtures.page("https://site.ru",
                "Политика конфиденциальности и обработки персональных данных", false,
                List.of(), List.of(), List.of(), "<html></html>");

        assertThat(rule.evaluate(TestFixtures.ctx(page))).isEmpty();
    }

    @Test
    void silentWhenNoPages() {
        assertThat(rule.evaluate(TestFixtures.ctx())).isEmpty();
    }
}
