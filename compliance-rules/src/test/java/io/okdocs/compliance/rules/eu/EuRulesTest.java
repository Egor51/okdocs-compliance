package io.okdocs.compliance.rules.eu;

import io.okdocs.compliance.contracts.crawler.FormInfo;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EuRulesTest {

    private static ScanAnalysisContext euCtx(PageAnalysisResult... pages) {
        return TestFixtures.ctxFor(ScanJurisdiction.EU, pages);
    }

    private static PageAnalysisResult page(String text, List<String> links, List<String> scripts,
                                           List<FormInfo> forms) {
        return TestFixtures.page("https://site.eu/", text, false, forms, links, scripts, "<html></html>");
    }

    @Nested
    class PrivacyNotice {
        private final EuPrivacyNoticeMissingRule rule = new EuPrivacyNoticeMissingRule();

        @Test
        void flagsWhenNoPrivacyNotice() {
            var ctx = euCtx(page("Welcome to our shop", List.of(), List.of(), List.of()));
            assertThat(rule.evaluate(ctx)).extracting(RuleFact::code)
                    .containsExactly("EU_PRIVACY_NOTICE_MISSING");
        }

        @Test
        void passesWhenPrivacyNoticeLinkPresent() {
            var ctx = euCtx(page("Welcome", List.of("/privacy-policy"), List.of(), List.of()));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }

        @Test
        void passesOnGermanDatenschutzText() {
            var ctx = euCtx(page("Unsere Datenschutzerklärung gilt", List.of(), List.of(), List.of()));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class ControllerIdentity {
        private final EuControllerIdentityMissingRule rule = new EuControllerIdentityMissingRule();

        @Test
        void notApplicableWithoutDataForms() {
            var ctx = euCtx(page("No forms here", List.of(), List.of(), List.of()));
            assertThat(rule.appliesTo(ctx)).isFalse();
        }

        @Test
        void flagsWhenFormsButNoController() {
            var form = TestFixtures.dataFormNoConsent("/lead");
            var ctx = euCtx(page("Contact us", List.of(), List.of(), List.of(form)));
            assertThat(rule.appliesTo(ctx)).isTrue();
            assertThat(rule.evaluate(ctx)).extracting(RuleFact::code)
                    .containsExactly("EU_CONTROLLER_IDENTITY_MISSING");
        }

        @Test
        void passesWhenControllerDisclosed() {
            var form = TestFixtures.dataFormNoConsent("/lead");
            var ctx = euCtx(page("The data controller is Acme Ltd, registered office London.",
                    List.of(), List.of(), List.of(form)));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class DataSubjectRights {
        private final EuDataSubjectRightsMissingRule rule = new EuDataSubjectRightsMissingRule();

        @Test
        void flagsWhenRightsNotMentioned() {
            var ctx = euCtx(page("We sell shoes.", List.of(), List.of(), List.of()));
            assertThat(rule.evaluate(ctx)).extracting(RuleFact::code)
                    .containsExactly("EU_DATA_SUBJECT_RIGHTS_MISSING");
        }

        @Test
        void passesWhenRightsMentioned() {
            var ctx = euCtx(page("You have the right to access and erasure of your data.",
                    List.of(), List.of(), List.of()));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class ThirdCountryTracker {
        private final EuThirdCountryTrackerRiskRule rule = new EuThirdCountryTrackerRiskRule();

        @Test
        void flagsUsProviderTracker() {
            var ctx = euCtx(TestFixtures.pageWithScripts("https://site.eu/",
                    List.of("www.google-analytics.com")));
            List<RuleFact> facts = rule.evaluate(ctx);
            assertThat(facts).extracting(RuleFact::code).containsExactly("EU_THIRD_COUNTRY_TRACKER_RISK");
            assertThat(facts.get(0).evidence()).contains("Google (US)");
        }

        @Test
        void ignoresUnknownDomains() {
            var ctx = euCtx(TestFixtures.pageWithScripts("https://site.eu/", List.of("cdn.self.eu")));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }
}
