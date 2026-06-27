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
 * The privacy information does not mention data-subject rights. GDPR Art. 13(2)(b)/15–22 require the
 * controller to inform data subjects of their rights (access, rectification, erasure, restriction,
 * portability, objection, withdrawal of consent). Static detection by text heuristics
 * ({@link EuPatterns}). EU baseline rule (layer {@code EU}).
 */
public final class EuDataSubjectRightsMissingRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "EU_DATA_SUBJECT_RIGHTS_MISSING",
            ScanJurisdiction.EU,
            FindingSeverity.MEDIUM,
            FindingCategory.DOCUMENTS,
            "Data-subject rights not disclosed",
            null,
            "GDPR Art. 13(2)(b), Art. 15–22 (rights of the data subject)",
            "No mention of data-subject rights (access, rectification, erasure, restriction, "
                    + "portability, objection, or withdrawal of consent) was found. Controllers must inform "
                    + "data subjects of these rights and how to exercise them.",
            "List the data-subject rights in the privacy notice and explain how to exercise them "
                    + "(e.g. a contact channel or request form), including the right to lodge a complaint "
                    + "with a supervisory authority.",
            "Data-subject rights disclosed",
            "The privacy information mentions data-subject rights under the GDPR.");

    @Override
    public Set<JurisdictionLayer> supportedLayers() {
        return Set.of(JurisdictionLayer.EU);
    }

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        if (pages.isEmpty() || EuPatterns.hasDataSubjectRights(ctx)) {
            return List.of();
        }
        return List.of(new RuleFact(
                DEFINITION.code(),
                "No mention of data-subject rights (access, erasure, rectification, objection, etc.) "
                        + "was found in the site's privacy information.",
                pages.get(0).url(),
                SourceType.HTML,
                RuleSupport.evidenceType(ctx),
                null,
                "data-subject-rights-absent",
                VerificationStatus.UNVERIFIED));
    }
}
