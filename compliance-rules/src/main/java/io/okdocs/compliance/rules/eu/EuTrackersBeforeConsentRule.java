package io.okdocs.compliance.rules.eu;

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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Trackers are still loaded after the user rejects consent. ePrivacy Art. 5(3) requires prior consent
 * for non-essential storage/access; if known trackers keep firing after an explicit reject, consent
 * is not respected. Consent-scenario rule (Фаза 4 data): matches {@code afterRejectTrackerHosts}
 * against {@link TrackerCatalog}. Strongest consent evidence — the user said no, trackers ran anyway.
 * EU baseline (layer {@code EU}). NOT_EVALUATED when no consent scenario.
 */
public final class EuTrackersBeforeConsentRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "EU_TRACKERS_BEFORE_CONSENT",
            ScanJurisdiction.EU,
            FindingSeverity.HIGH,
            FindingCategory.TRACKERS,
            "Trackers still load after consent is rejected",
            null,
            "ePrivacy Directive Art. 5(3); GDPR Art. 6, Art. 7 (consent before processing)",
            "After rejecting cookies in the banner, known third-party trackers continue to load and "
                    + "transmit data. Non-essential trackers must not run before — or despite — a refusal of "
                    + "consent.",
            "1. Gate all non-essential trackers (analytics, advertising, pixels) behind an explicit "
                    + "opt-in. 2. Ensure a reject action actually blocks them. 3. Audit tag-manager triggers "
                    + "so they respect the consent signal.",
            "No trackers after consent rejection",
            "No known trackers were observed loading after consent was rejected.");

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
            List<String> hosts = page.consentScenario().afterRejectTrackerHosts();
            if (hosts == null || hosts.isEmpty()) {
                continue;
            }
            Set<String> named = new LinkedHashSet<>();
            for (String host : hosts) {
                TrackerCatalog.lookup(host).ifPresent(info -> named.add(info.provider()));
            }
            if (named.isEmpty()) {
                continue; // неизвестные хосты — не подтверждаем как трекеры (jurisdiction-neutral каталог)
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Trackers still loading after consent rejection: " + String.join(", ", named) + ".",
                    page.url(),
                    SourceType.HTML,
                    EvidenceType.DYNAMIC_RENDER,
                    0.90,
                    String.join(",", named),
                    VerificationStatus.DETECTED));
        }
        return facts;
    }
}
