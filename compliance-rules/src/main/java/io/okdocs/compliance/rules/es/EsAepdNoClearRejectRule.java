package io.okdocs.compliance.rules.es;

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
 * ES (AEPD) overlay: the cookie banner lacks a clear option to reject cookies. The AEPD's cookie
 * guidance requires a "Rechazar" option as accessible as "Aceptar" on the first layer, and forbids
 * making rejection harder. Overlay layer {@code ES} on top of the EU baseline. Consent-scenario rule
 * (Фаза 4 data).
 */
public final class EsAepdNoClearRejectRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "ES_AEPD_NO_CLEAR_REJECT_OPTION",
            ScanJurisdiction.ES,
            FindingSeverity.HIGH,
            FindingCategory.COOKIES,
            "No clear option to reject cookies (AEPD)",
            null,
            "LSSI art. 22.2; RGPD art. 7; Guía de cookies AEPD",
            "The cookie banner does not provide a clear, first-layer option to reject cookies as "
                    + "accessible as the accept option. The AEPD cookie guidance requires a 'Rechazar' control "
                    + "equivalent to 'Aceptar'.",
            "Add a 'Rechazar' button on the first banner layer with the same prominence as 'Aceptar'. "
                    + "Do not require additional steps to refuse cookies.",
            "A clear reject option is present",
            "The banner offers a reject control as accessible as the accept control.");

    @Override
    public Set<JurisdictionLayer> supportedLayers() {
        return Set.of(JurisdictionLayer.ES);
    }

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public boolean appliesTo(ScanAnalysisContext ctx) {
        return ConsentSupport.bannerInspectionAvailable(ctx);
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (PageAnalysisResult page : ConsentSupport.pagesWithBannerInspection(ctx)) {
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
                            ? "No 'Rechazar' control on the first banner layer."
                            : "The reject control is less accessible than accept.")
                            + (banner.cmpProvider() != null ? " CMP: " + banner.cmpProvider() + "." : ""),
                    page.url(),
                    SourceType.HTML,
                    EvidenceType.DYNAMIC_RENDER,
                    0.85,
                    rejectMissing ? "aepd-reject-absent" : "aepd-reject-not-equal",
                    VerificationStatus.DETECTED,
                    rejectMissing ? "ES_AEPD_REJECT_ABSENT" : "ES_AEPD_REJECT_UNEQUAL",
                    java.util.Map.of("cmp", ConsentSupport.cmpSuffix(banner))));
        }
        return facts;
    }
}
