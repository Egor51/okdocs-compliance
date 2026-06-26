package io.okdocs.compliance.rules.eu;

import io.okdocs.compliance.contracts.crawler.ConsentBannerInfo;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The cookie banner offers no equally prominent way to reject non-essential cookies. EDPB guidelines
 * require that refusing consent be as easy as giving it; an "Accept all" button with no same-level
 * "Reject all" makes consent invalid (not freely given). Consent-scenario rule (Фаза 4 data): reads
 * {@link ConsentBannerInfo}. EU baseline (layer {@code EU}). NOT_EVALUATED when no consent scenario.
 */
public final class EuNoRejectOptionRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "EU_NO_REJECT_OPTION",
            ScanJurisdiction.EU,
            FindingSeverity.HIGH,
            FindingCategory.COOKIES,
            "No equally easy option to reject cookies",
            null,
            "GDPR Art. 4(11), Art. 7 (freely given consent); ePrivacy Art. 5(3); EDPB Guidelines 03/2022",
            "The cookie banner lets users accept cookies but offers no equally prominent way to reject "
                    + "them. Consent must be as easy to refuse as to give; an unbalanced banner (accept-only, "
                    + "or reject hidden behind extra steps) renders consent invalid.",
            "Add a 'Reject all' button at the same level and prominence as 'Accept all' on the first "
                    + "banner layer. Do not hide refusal behind 'Manage preferences'.",
            "Reject option is available",
            "The cookie banner offers a reject option at the same level as accept.");

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
            ConsentBannerInfo banner = page.consentScenario().banner();
            if (!banner.bannerFound() || !banner.acceptButtonFound()) {
                continue; // нет баннера/accept — это не «отказ затруднён», а другой случай
            }
            boolean rejectMissing = !banner.rejectButtonFound();
            boolean rejectUnequal = banner.rejectButtonFound() && !banner.rejectSameLevelAsAccept();
            if (!rejectMissing && !rejectUnequal) {
                continue;
            }
            String reason = rejectMissing
                    ? "Banner has an accept option but no reject option."
                    : "Reject option is not at the same level as accept (hidden behind extra steps).";
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    reason + (banner.cmpProvider() != null ? " CMP: " + banner.cmpProvider() + "." : ""),
                    page.url(),
                    SourceType.HTML,
                    EvidenceType.DYNAMIC_RENDER,
                    0.85,
                    rejectMissing ? "reject-button-absent" : "reject-not-same-level",
                    VerificationStatus.DETECTED));
        }
        return facts;
    }
}
