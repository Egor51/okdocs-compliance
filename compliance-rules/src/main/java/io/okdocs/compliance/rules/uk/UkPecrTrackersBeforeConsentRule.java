package io.okdocs.compliance.rules.uk;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.crawler.ObservedCookie;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.JurisdictionLayer;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleApplicability;
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
 * Trackers are still loaded after the user rejects consent. PECR reg. 6 requires consent before
 * non-essential storage/access; trackers firing despite a reject breach that requirement. UK branch
 * (layer {@code UK}). Consent-scenario rule (Фаза 4 data): matches {@code afterRejectTrackerHosts}
 * against {@link TrackerCatalog}. NOT_EVALUATED when no consent scenario.
 */
public final class UkPecrTrackersBeforeConsentRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "UK_PECR_TRACKERS_BEFORE_CONSENT",
            ScanJurisdiction.UK,
            FindingSeverity.HIGH,
            FindingCategory.TRACKERS,
            "Trackers still load after consent is rejected",
            null,
            "PECR 2003 reg. 6; UK GDPR Art. 6, Art. 7",
            "After rejecting cookies in the banner, known third-party trackers continue to load. "
                    + "Non-essential trackers must not run before — or despite — a refusal of consent.",
            "1. Gate all non-essential trackers behind an explicit opt-in. 2. Ensure a reject action "
                    + "blocks them. 3. Audit tag-manager triggers so they respect the consent signal.",
            "No trackers after consent rejection",
            "No known trackers were observed loading after consent was rejected.");

    @Override
    public Set<JurisdictionLayer> supportedLayers() {
        return Set.of(JurisdictionLayer.UK);
    }

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public boolean appliesTo(ScanAnalysisContext ctx) {
        return ConsentSupport.postRejectSnapshotAvailable(ctx);
    }

    @Override
    public RuleApplicability applicability(ScanAnalysisContext ctx) {
        return ConsentSupport.postRejectApplicability(ctx);
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (PageAnalysisResult page : ConsentSupport.pagesWithPostRejectSnapshot(ctx)) {
            List<String> hosts = page.consentScenario().afterRejectTrackerHosts();
            Set<String> named = new LinkedHashSet<>();
            if (hosts != null) {
                for (String host : hosts) {
                    TrackerCatalog.lookup(host).ifPresent(info -> named.add(info.provider()));
                }
            }
            for (ObservedCookie cookie : page.consentScenario().afterRejectCookies()) {
                if (TrackerCookieNames.isTracker(cookie.name())) {
                    named.add("cookie:" + cookie.name());
                }
            }
            for (String key : page.consentScenario().afterRejectStorageKeys()) {
                if (TrackerCookieNames.isTracker(key)) {
                    named.add("storage:" + key);
                }
            }
            if (named.isEmpty()) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Trackers still loading after consent rejection: " + String.join(", ", named) + ".",
                    page.url(),
                    SourceType.HTML,
                    EvidenceType.DYNAMIC_RENDER,
                    0.90,
                    String.join(",", named),
                    VerificationStatus.DETECTED,
                    "TRACKERS_AFTER_REJECT",
                    java.util.Map.of("items", named)));
        }
        return facts;
    }
}
