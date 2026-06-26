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
 * No privacy notice found on the site. GDPR Art. 13/14 require a transparent privacy notice where
 * personal data is collected. Static detection by link/text heuristics ({@link EuPatterns}); no
 * consent-scenario crawling. EU baseline rule (layer {@code EU}) — also runs on DE/FR/ES scans.
 */
public final class EuPrivacyNoticeMissingRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "EU_PRIVACY_NOTICE_MISSING",
            ScanJurisdiction.EU,
            FindingSeverity.HIGH,
            FindingCategory.DOCUMENTS,
            "No privacy notice found",
            null,
            "GDPR Art. 13–14 (information to be provided to the data subject)",
            "Where a controller collects personal data, it must provide a transparent privacy notice "
                    + "describing the processing. No privacy policy / data-protection notice could be found "
                    + "in the site links or page text, which is a transparency failure under the GDPR.",
            "1. Publish a GDPR privacy notice on a stable URL. 2. Link it in the footer and next to data "
                    + "collection forms. 3. Cover controller identity, purposes, legal basis, retention, "
                    + "recipients, transfers and data-subject rights. 4. Keep it accessible without login.",
            "Privacy notice found",
            "A privacy notice / data-protection statement was found on the site.");

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
        if (pages.isEmpty() || EuPatterns.hasPrivacyNotice(ctx)) {
            return List.of();
        }
        return List.of(new RuleFact(
                DEFINITION.code(),
                "No privacy notice link or text found in the site footer, navigation or page content.",
                pages.get(0).url(),
                SourceType.HTML,
                RuleSupport.evidenceType(ctx),
                null,
                "privacy-notice-absent",
                VerificationStatus.UNVERIFIED));
    }
}
