package io.okdocs.compliance.rules.uk;

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
 * The cookie banner offers no equally easy way to reject non-essential cookies. PECR reg. 6 requires
 * informed consent before non-essential storage; ICO guidance requires reject to be as prominent as
 * accept. UK branch (layer {@code UK}). Consent-scenario rule (Фаза 4 data). NOT_EVALUATED when no
 * consent scenario.
 */
public final class UkPecrNoRejectOptionRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "UK_PECR_NO_REJECT_OPTION",
            ScanJurisdiction.UK,
            FindingSeverity.HIGH,
            FindingCategory.COOKIES,
            "No equally easy option to reject cookies",
            null,
            "PECR 2003 reg. 6; UK GDPR Art. 4(11), Art. 7; ICO Cookie Guidance",
            "The cookie banner lets users accept cookies but offers no equally prominent way to reject "
                    + "them. ICO guidance requires that refusing be as easy as accepting; an unbalanced banner "
                    + "makes consent invalid.",
            "Add a 'Reject all' control at the same level and prominence as 'Accept all' on the first "
                    + "banner layer; do not hide refusal behind 'Manage'.",
            "Reject option is available",
            "The cookie banner offers a reject option at the same level as accept.");

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
        return ConsentSupport.scenarioAvailable(ctx);
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (PageAnalysisResult page : ConsentSupport.pagesWithScenario(ctx)) {
            ConsentBannerInfo banner = page.consentScenario().banner();
            if (!banner.bannerFound() || !banner.acceptButtonFound()) {
                continue;
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
                    VerificationStatus.DETECTED,
                    rejectMissing ? "NO_REJECT_ABSENT" : "NO_REJECT_UNEQUAL",
                    java.util.Map.of("cmp", ConsentSupport.cmpSuffix(banner))));
        }
        return facts;
    }
}
