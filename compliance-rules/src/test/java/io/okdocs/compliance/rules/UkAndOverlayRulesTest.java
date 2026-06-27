package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.crawler.ConsentBannerInfo;
import io.okdocs.compliance.contracts.crawler.ConsentScenarioResult;
import io.okdocs.compliance.contracts.crawler.ObservedCookie;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.rules.de.DeTdddgTerminalAccessRule;
import io.okdocs.compliance.rules.es.EsAepdNoClearRejectRule;
import io.okdocs.compliance.rules.fr.FrCnilRejectNotAsEasyRule;
import io.okdocs.compliance.rules.uk.UkPecrNoRejectOptionRule;
import io.okdocs.compliance.rules.uk.UkPecrTrackersBeforeConsentRule;
import io.okdocs.compliance.rules.uk.UkPrivacyNoticeMissingRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** UK-ветка + overlays DE/FR/ES (Фаза 6). */
class UkAndOverlayRulesTest {

    private static ScanAnalysisContext ctxWithConsent(ScanJurisdiction j, ConsentScenarioResult s) {
        return TestFixtures.ctxFor(j, TestFixtures.dynamicPageWithConsent("https://site/", s));
    }

    private static ConsentBannerInfo banner(boolean accept, boolean reject, boolean sameLevel) {
        return new ConsentBannerInfo(true, accept, reject, false, false, sameLevel, false, "Cookiebot");
    }

    private static ConsentScenarioResult rejectScenario(List<ObservedCookie> afterReject,
                                                        List<String> afterRejectHosts,
                                                        ConsentBannerInfo banner) {
        return new ConsentScenarioResult(banner, afterReject, afterRejectHosts, List.of(), true);
    }

    @Test
    void ukPrivacyNoticeMissingFlagsWhenAbsent() {
        var rule = new UkPrivacyNoticeMissingRule();
        var ctx = TestFixtures.ctxFor(ScanJurisdiction.UK,
                TestFixtures.page("https://site/", "We sell tea.", false, List.of(), List.of(),
                        List.of(), "<html></html>"));
        assertThat(rule.evaluate(ctx)).extracting(RuleFact::code)
                .containsExactly("UK_PRIVACY_NOTICE_MISSING");
    }

    @Test
    void ukPrivacyNoticePassesWithNoticeLink() {
        var rule = new UkPrivacyNoticeMissingRule();
        var ctx = TestFixtures.ctxFor(ScanJurisdiction.UK,
                TestFixtures.page("https://site/", "x", false, List.of(), List.of("/privacy-policy"),
                        List.of(), "<html></html>"));
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void ukPecrNoRejectFlagsAcceptOnlyBanner() {
        var rule = new UkPecrNoRejectOptionRule();
        var ctx = ctxWithConsent(ScanJurisdiction.UK,
                rejectScenario(List.of(), List.of(), banner(true, false, false)));
        assertThat(rule.evaluate(ctx)).extracting(RuleFact::code)
                .containsExactly("UK_PECR_NO_REJECT_OPTION");
    }

    @Test
    void ukPecrNoRejectNotEvaluatedWithoutScenario() {
        var rule = new UkPecrNoRejectOptionRule();
        var ctx = TestFixtures.ctxFor(ScanJurisdiction.UK, TestFixtures.simplePage("https://site/"));
        assertThat(rule.appliesTo(ctx)).isFalse();
    }

    @Test
    void ukPecrTrackersAfterRejectFlagged() {
        var rule = new UkPecrTrackersBeforeConsentRule();
        var ctx = ctxWithConsent(ScanJurisdiction.UK,
                rejectScenario(List.of(), List.of("www.google-analytics.com"), banner(true, true, true)));
        assertThat(rule.evaluate(ctx)).extracting(RuleFact::code)
                .containsExactly("UK_PECR_TRACKERS_BEFORE_CONSENT");
    }

    @Test
    void frCnilRejectNotAsEasyFlagsUnequalReject() {
        var rule = new FrCnilRejectNotAsEasyRule();
        var ctx = ctxWithConsent(ScanJurisdiction.FR,
                rejectScenario(List.of(), List.of(), banner(true, true, false)));
        assertThat(rule.evaluate(ctx)).extracting(RuleFact::code)
                .containsExactly("FR_CNIL_REJECT_NOT_AS_EASY_AS_ACCEPT");
    }

    @Test
    void deTdddgFlagsTrackerCookieAfterReject() {
        var rule = new DeTdddgTerminalAccessRule();
        var cookie = new ObservedCookie("_ga", "site", true, false, "Lax", false);
        var ctx = ctxWithConsent(ScanJurisdiction.DE,
                rejectScenario(List.of(cookie), List.of(), banner(true, true, true)));
        assertThat(rule.evaluate(ctx)).extracting(RuleFact::code)
                .containsExactly("DE_TDDDG_TERMINAL_ACCESS_WITHOUT_CONSENT");
    }

    @Test
    void esAepdFlagsMissingReject() {
        var rule = new EsAepdNoClearRejectRule();
        var ctx = ctxWithConsent(ScanJurisdiction.ES,
                rejectScenario(List.of(), List.of(), banner(true, false, false)));
        assertThat(rule.evaluate(ctx)).extracting(RuleFact::code)
                .containsExactly("ES_AEPD_NO_CLEAR_REJECT_OPTION");
    }

    @Test
    void overlayPassesWhenRejectIsEqual() {
        // FR/ES overlay не срабатывают, если reject равноценен accept.
        var ctx = ctxWithConsent(ScanJurisdiction.FR,
                rejectScenario(List.of(), List.of(), banner(true, true, true)));
        assertThat(new FrCnilRejectNotAsEasyRule().evaluate(ctx)).isEmpty();
        var esCtx = ctxWithConsent(ScanJurisdiction.ES,
                rejectScenario(List.of(), List.of(), banner(true, true, true)));
        assertThat(new EsAepdNoClearRejectRule().evaluate(esCtx)).isEmpty();
    }
}
