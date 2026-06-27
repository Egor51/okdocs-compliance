package io.okdocs.compliance.rules.eu;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.JurisdictionLayer;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.RuleSupport;

import java.util.List;
import java.util.Set;

/**
 * The identity of the data controller is not disclosed. GDPR Art. 13(1)(a) requires the controller's
 * identity and contact details to be provided. Static detection by text heuristics
 * ({@link EuPatterns}). EU baseline rule (layer {@code EU}). Applies only when the site collects
 * personal data (has data forms) — otherwise there is no controller-disclosure obligation to flag.
 */
public final class EuControllerIdentityMissingRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "EU_CONTROLLER_IDENTITY_MISSING",
            ScanJurisdiction.EU,
            FindingSeverity.MEDIUM,
            FindingCategory.DOCUMENTS,
            "Data controller identity not disclosed",
            null,
            "GDPR Art. 13(1)(a) (identity and contact details of the controller)",
            "The site collects personal data but does not clearly disclose who the data controller is "
                    + "(legal entity, registered address or contact). Data subjects must be able to identify "
                    + "and contact the controller responsible for processing their data.",
            "Disclose the controller's identity in the privacy notice: legal name, registered address, "
                    + "and a contact point (email/postal). Where applicable, include the DPO contact details.",
            "Controller identity disclosed",
            "The data controller's identity / contact details were found on the site.");

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
        // Без форм сбора ПДн нет обязанности раскрытия контролёра — не помечаем PASSED/FAILED.
        return RuleSupport.hasDataForms(ctx);
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        if (pages.isEmpty() || EuPatterns.hasControllerIdentity(ctx)) {
            return List.of();
        }
        return List.of(new RuleFact(
                DEFINITION.code(),
                "The site collects personal data but no data-controller identity or contact details "
                        + "were found.",
                pages.get(0).url(),
                SourceType.HTML,
                RuleSupport.evidenceType(ctx),
                null,
                "controller-identity-absent",
                VerificationStatus.UNVERIFIED,
                "EU_CONTROLLER_IDENTITY_MISSING",
                java.util.Map.of()));
    }
}
