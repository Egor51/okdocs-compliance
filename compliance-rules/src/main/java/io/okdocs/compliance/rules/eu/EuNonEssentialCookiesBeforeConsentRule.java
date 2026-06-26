package io.okdocs.compliance.rules.eu;

import io.okdocs.compliance.contracts.crawler.ObservedCookie;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.JurisdictionLayer;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.common.ConsentSupport;
import io.okdocs.compliance.rules.common.TrackerCookieNames;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Non-essential (tracking) cookies remain set after the user rejects consent. ePrivacy Art. 5(3)
 * requires prior consent for non-essential cookies; a tracking cookie surviving an explicit reject
 * means refusal is not honoured. Consent-scenario rule (Фаза 4 data): inspects
 * {@code afterRejectCookies} for known tracker cookie names. EU baseline (layer {@code EU}).
 */
public final class EuNonEssentialCookiesBeforeConsentRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "EU_NON_ESSENTIAL_COOKIES_BEFORE_CONSENT",
            ScanJurisdiction.EU,
            FindingSeverity.HIGH,
            FindingCategory.COOKIES,
            "Non-essential cookies remain after consent is rejected",
            null,
            "ePrivacy Directive Art. 5(3); GDPR Art. 6, Art. 7 (consent before processing)",
            "After rejecting cookies in the banner, non-essential tracking cookies are still present. "
                    + "Only strictly necessary cookies may be set without consent; tracking cookies must be "
                    + "removed or never set when consent is refused.",
            "1. Do not set non-essential cookies until consent is given. 2. Ensure a reject action "
                    + "clears or prevents tracking cookies. 3. Classify cookies and document the essential set.",
            "No non-essential cookies after rejection",
            "No known tracking cookies were present after consent was rejected.");

    @Override
    public Set<JurisdictionLayer> supportedLayers() {
        return Set.of(JurisdictionLayer.EU);
    }

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public boolean appliesTo(ScanAnalysisContext ctx) {
        return ConsentSupport.scenarioAvailable(ctx);
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (PageAnalysisResult page : ConsentSupport.pagesWithScenario(ctx)) {
            List<ObservedCookie> cookies = page.consentScenario().afterRejectCookies();
            if (cookies == null || cookies.isEmpty()) {
                continue;
            }
            Set<String> trackers = new LinkedHashSet<>();
            for (ObservedCookie c : cookies) {
                if (TrackerCookieNames.isTracker(c.name())) {
                    trackers.add(c.name());
                }
            }
            if (trackers.isEmpty()) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Tracking cookies present after consent rejection: " + String.join(", ", trackers) + ".",
                    page.url(),
                    SourceType.HTTP_HEADER,
                    EvidenceType.DYNAMIC_RENDER,
                    0.90,
                    String.join(",", trackers),
                    VerificationStatus.DETECTED));
        }
        return facts;
    }
}
