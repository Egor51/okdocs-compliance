package io.okdocs.compliance.rules.fr;

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
 * FR (CNIL) overlay: refusing cookies must be as easy as accepting them. The CNIL has fined sites
 * (Google, Facebook) specifically for an "Accept all" button with no equivalent "Reject all" on the
 * first layer. Overlay layer {@code FR} — adds French strictness on top of the EU baseline
 * (EU_NO_REJECT_OPTION), it does not replace it. Consent-scenario rule (Фаза 4 data).
 */
public final class FrCnilRejectNotAsEasyRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "FR_CNIL_REJECT_NOT_AS_EASY_AS_ACCEPT",
            ScanJurisdiction.FR,
            FindingSeverity.HIGH,
            FindingCategory.COOKIES,
            "Refusing cookies is not as easy as accepting (CNIL)",
            null,
            "Loi Informatique et Libertés art. 82; CNIL délibération; RGPD art. 7",
            "The cookie banner makes accepting cookies easier than refusing them (accept-only first "
                    + "layer, or reject hidden behind 'Manage'). The CNIL requires a reject mechanism as "
                    + "simple and prominent as the accept mechanism and has issued significant fines for this.",
            "Provide a 'Tout refuser' button on the first banner layer, with the same prominence as "
                    + "'Tout accepter'. Do not require extra clicks to refuse.",
            "Refusing cookies is as easy as accepting",
            "The banner offers a reject control as prominent as the accept control.");

    @Override
    public Set<JurisdictionLayer> supportedLayers() {
        return Set.of(JurisdictionLayer.FR);
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
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    (rejectMissing
                            ? "No 'reject all' control on the first banner layer."
                            : "The reject control is less prominent than accept (extra steps required).")
                            + (banner.cmpProvider() != null ? " CMP: " + banner.cmpProvider() + "." : ""),
                    page.url(),
                    SourceType.HTML,
                    EvidenceType.DYNAMIC_RENDER,
                    0.85,
                    rejectMissing ? "cnil-reject-absent" : "cnil-reject-not-equal",
                    VerificationStatus.DETECTED,
                    rejectMissing ? "FR_CNIL_REJECT_ABSENT" : "FR_CNIL_REJECT_UNEQUAL",
                    java.util.Map.of("cmp", ConsentSupport.cmpSuffix(banner))));
        }
        return facts;
    }
}
