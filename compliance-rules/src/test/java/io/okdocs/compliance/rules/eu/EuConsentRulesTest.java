package io.okdocs.compliance.rules.eu;

import io.okdocs.compliance.contracts.crawler.ConsentBannerInfo;
import io.okdocs.compliance.contracts.crawler.ConsentScenarioResult;
import io.okdocs.compliance.contracts.crawler.ObservedCookie;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EuConsentRulesTest {

    private static ScanAnalysisContext euCtx(ConsentScenarioResult scenario) {
        return TestFixtures.ctxFor(ScanJurisdiction.EU,
                TestFixtures.dynamicPageWithConsent("https://site.eu/", scenario));
    }

    /** Контекст без consent-сценария (STATIC / scenario==null) — для проверки NOT_EVALUATED. */
    private static ScanAnalysisContext euCtxNoScenario() {
        return TestFixtures.ctxFor(ScanJurisdiction.EU, TestFixtures.simplePage("https://site.eu/"));
    }

    private static ConsentBannerInfo banner(boolean accept, boolean reject, boolean sameLevel,
                                            boolean prechecked) {
        return new ConsentBannerInfo(true, accept, reject, false, false, sameLevel, prechecked, "OneTrust");
    }

    private static ObservedCookie cookie(String name) {
        return new ObservedCookie(name, "site.eu", true, false, "Lax", false);
    }

    @Nested
    class NoRejectOption {
        private final EuNoRejectOptionRule rule = new EuNoRejectOptionRule();

        @Test
        void notEvaluatedWithoutScenario() {
            assertThat(rule.appliesTo(euCtxNoScenario())).isFalse();
        }

        @Test
        void flagsWhenAcceptButNoReject() {
            var scenario = new ConsentScenarioResult(banner(true, false, false, false),
                    List.of(), List.of(), List.of(), true);
            assertThat(rule.evaluate(euCtx(scenario))).extracting(RuleFact::code)
                    .containsExactly("EU_NO_REJECT_OPTION");
        }

        @Test
        void flagsWhenRejectNotSameLevel() {
            var scenario = new ConsentScenarioResult(banner(true, true, false, false),
                    List.of(), List.of(), List.of(), true);
            assertThat(rule.evaluate(euCtx(scenario))).extracting(RuleFact::code)
                    .containsExactly("EU_NO_REJECT_OPTION");
        }

        @Test
        void passesWhenRejectSameLevel() {
            var scenario = new ConsentScenarioResult(banner(true, true, true, false),
                    List.of(), List.of(), List.of(), true);
            assertThat(rule.evaluate(euCtx(scenario))).isEmpty();
        }
    }

    @Nested
    class Prechecked {
        private final EuConsentPrecheckedRule rule = new EuConsentPrecheckedRule();

        @Test
        void flagsPrecheckedToggles() {
            var scenario = new ConsentScenarioResult(banner(true, true, true, true),
                    List.of(), List.of(), List.of(), true);
            assertThat(rule.evaluate(euCtx(scenario))).extracting(RuleFact::code)
                    .containsExactly("EU_CONSENT_PRECHECKED_OR_DEFAULT_ON");
        }

        @Test
        void passesWhenNoPrecheck() {
            var scenario = new ConsentScenarioResult(banner(true, true, true, false),
                    List.of(), List.of(), List.of(), true);
            assertThat(rule.evaluate(euCtx(scenario))).isEmpty();
        }
    }

    @Nested
    class TrackersAfterReject {
        private final EuTrackersBeforeConsentRule rule = new EuTrackersBeforeConsentRule();

        @Test
        void flagsKnownTrackerAfterReject() {
            var scenario = new ConsentScenarioResult(banner(true, true, true, false),
                    List.of(), List.of("www.google-analytics.com"), List.of(), true);
            List<RuleFact> facts = rule.evaluate(euCtx(scenario));
            assertThat(facts).extracting(RuleFact::code).containsExactly("EU_TRACKERS_BEFORE_CONSENT");
            assertThat(facts.get(0).evidence()).contains("Google");
        }

        @Test
        void ignoresUnknownHostAfterReject() {
            var scenario = new ConsentScenarioResult(banner(true, true, true, false),
                    List.of(), List.of("cdn.self.eu"), List.of(), true);
            assertThat(rule.evaluate(euCtx(scenario))).isEmpty();
        }
    }

    @Nested
    class NonEssentialCookiesAfterReject {
        private final EuNonEssentialCookiesBeforeConsentRule rule =
                new EuNonEssentialCookiesBeforeConsentRule();

        @Test
        void flagsTrackingCookieAfterReject() {
            var scenario = new ConsentScenarioResult(banner(true, true, true, false),
                    List.of(cookie("_ga")), List.of(), List.of(), true);
            assertThat(rule.evaluate(euCtx(scenario))).extracting(RuleFact::code)
                    .containsExactly("EU_NON_ESSENTIAL_COOKIES_BEFORE_CONSENT");
        }

        @Test
        void passesWhenOnlyEssentialCookieAfterReject() {
            var scenario = new ConsentScenarioResult(banner(true, true, true, false),
                    List.of(cookie("session_id")), List.of(), List.of(), true);
            assertThat(rule.evaluate(euCtx(scenario))).isEmpty();
        }
    }
}
