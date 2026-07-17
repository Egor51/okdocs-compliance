package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.ConsentBannerInfo;
import io.okdocs.compliance.contracts.crawler.ConsentScenarioResult;
import io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason;
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

    @Test
    void reportsConcreteReasonWhenRejectWasNotFound() {
        var scenario = ConsentScenarioResult.failed(
                banner(false), true, false, false, ConsentScenarioFailureReason.REJECT_NOT_FOUND);

        var result = new RuleEngine(List.of(rule)).evaluate(TestFixtures.ctx(
                TestFixtures.dynamicPageWithConsent("https://site.ru", scenario)));

        assertThat(result.outcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo(RuleOutcomeStatus.NOT_EVALUATED);
            assertThat(outcome.messageKey()).isEqualTo("NOT_EVALUATED_CONSENT_REJECT_NOT_FOUND");
            assertThat(outcome.message()).contains("действие отказа не найдено");
        });
    }

    @Test
    void detectsTrackingIdentifierRemainingInLocalStorage() {
        var scenario = new ConsentScenarioResult(
                banner(true), List.of(), List.of(), List.of("local:_ga_client_id"), List.of(),
                List.of(), true, true, true, true, ConsentScenarioFailureReason.NONE, false, true);

        assertThat(rule.evaluate(TestFixtures.ctx(
                TestFixtures.dynamicPageWithConsent("https://site.ru", scenario))))
                .singleElement()
                .satisfies(f -> assertThat(f.matchedSignals()).contains("storage:local:_ga_client_id"));
    }

    private static ConsentBannerInfo banner(boolean rejectFound) {
        return new ConsentBannerInfo(true, true, rejectFound, false,
                false, false, false, null);
    }
}
