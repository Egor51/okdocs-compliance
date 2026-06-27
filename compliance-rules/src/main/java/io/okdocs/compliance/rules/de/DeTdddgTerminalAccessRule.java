package io.okdocs.compliance.rules.de;

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
import io.okdocs.compliance.rules.common.TrackerCatalog;
import io.okdocs.compliance.rules.common.TrackerCookieNames;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DE (TDDDG) overlay: storing or accessing information on the user's terminal equipment without
 * consent. TDDDG § 25 (formerly TTDSG) requires prior consent for any non-essential storage/access on
 * the device — covering both cookies and tracker network access. Evidence: non-essential cookies or
 * known trackers still present after an explicit reject. Overlay layer {@code DE} on top of the EU
 * baseline. Consent-scenario rule (Фаза 4 data).
 */
public final class DeTdddgTerminalAccessRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "DE_TDDDG_TERMINAL_ACCESS_WITHOUT_CONSENT",
            ScanJurisdiction.DE,
            FindingSeverity.HIGH,
            FindingCategory.COOKIES,
            "Storage/access on terminal equipment without consent (TDDDG § 25)",
            null,
            "TDDDG § 25 (ex-TTDSG § 25); DSGVO Art. 6",
            "After the user rejects consent, non-essential cookies or trackers still store or access "
                    + "information on the device. TDDDG § 25 requires prior consent for any such storage or "
                    + "access that is not strictly necessary.",
            "Ensure that rejecting consent blocks all non-essential cookies and trackers. Only strictly "
                    + "necessary storage/access may occur without consent.",
            "No terminal access without consent",
            "No non-essential storage or tracker access was observed after consent was rejected.");

    @Override
    public Set<JurisdictionLayer> supportedLayers() {
        return Set.of(JurisdictionLayer.DE);
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
            Set<String> evidence = new LinkedHashSet<>();
            for (ObservedCookie c : page.consentScenario().afterRejectCookies()) {
                if (TrackerCookieNames.isTracker(c.name())) {
                    evidence.add("cookie:" + c.name());
                }
            }
            for (String host : page.consentScenario().afterRejectTrackerHosts()) {
                TrackerCatalog.lookup(host).ifPresent(info -> evidence.add("tracker:" + info.provider()));
            }
            if (evidence.isEmpty()) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Non-essential storage/access after consent rejection: " + String.join(", ", evidence) + ".",
                    page.url(),
                    SourceType.HTML,
                    EvidenceType.DYNAMIC_RENDER,
                    0.90,
                    String.join(",", evidence),
                    VerificationStatus.DETECTED,
                    "DE_TDDDG_TERMINAL_ACCESS_WITHOUT_CONSENT",
                    java.util.Map.of("items", evidence)));
        }
        return facts;
    }
}
