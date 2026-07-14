package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.ConsentBannerInfo;
import io.okdocs.compliance.contracts.crawler.ConsentScenarioResult;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.RuleEngine;
import io.okdocs.compliance.rules.RuleOutcomeStatus;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentChoiceNotEffectiveRuleTest {

    private final ConsentChoiceNotEffectiveRule rule = new ConsentChoiceNotEffectiveRule();

    @Test
    void doesNotApplyWithoutDynamicConsentScenario() {
        assertThat(rule.appliesTo(TestFixtures.ctx(TestFixtures.simplePage("https://site.ru")))).isFalse();
        var result = new RuleEngine(List.of(rule)).evaluate(
                TestFixtures.ctx(TestFixtures.simplePage("https://site.ru")));
        assertThat(result.outcomes()).singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(RuleOutcomeStatus.NOT_EVALUATED));
    }

    @Test
    void detectsKnownTrackerContinuingAfterExecutedReject() {
        var scenario = new ConsentScenarioResult(banner(true), List.of(),
                List.of("mc.yandex.ru"), List.of(), true);

        assertThat(rule.evaluate(TestFixtures.ctx(
                TestFixtures.dynamicPageWithConsent("https://site.ru", scenario))))
                .singleElement().satisfies(f -> {
                    assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.DETECTED);
                    assertThat(f.matchedSignals()).contains("reject-executed", "Yandex");
                });
    }

    @Test
    void doesNotTreatMissingRejectButtonAsStandaloneRuViolation() {
        var scenario = new ConsentScenarioResult(banner(false), List.of(),
                List.of("mc.yandex.ru"), List.of(), true);

        assertThat(rule.evaluate(TestFixtures.ctx(
                TestFixtures.dynamicPageWithConsent("https://site.ru", scenario)))).isEmpty();
    }

    @Test
    void ignoresUnknownInfrastructureHostAfterReject() {
        var scenario = new ConsentScenarioResult(banner(true), List.of(),
                List.of("cdn.site.ru"), List.of(), true);

        assertThat(rule.evaluate(TestFixtures.ctx(
                TestFixtures.dynamicPageWithConsent("https://site.ru", scenario)))).isEmpty();
    }

    private static ConsentBannerInfo banner(boolean rejectFound) {
        return new ConsentBannerInfo(true, true, rejectFound, false,
                false, false, false, null);
    }
}
