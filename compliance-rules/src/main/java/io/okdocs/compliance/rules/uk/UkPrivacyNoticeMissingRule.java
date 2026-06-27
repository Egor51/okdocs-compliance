package io.okdocs.compliance.rules.uk;

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
import io.okdocs.compliance.rules.common.GdprPatterns;

import java.util.List;
import java.util.Set;

/**
 * No privacy notice found on the site. UK GDPR Art. 13/14 (mirroring EU GDPR, via DPA 2018) require a
 * transparent privacy notice. UK branch (layer {@code UK}) — does <b>not</b> inherit the EU baseline.
 * Detection reuses the neutral {@link GdprPatterns}; metadata is UK-specific.
 */
public final class UkPrivacyNoticeMissingRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "UK_PRIVACY_NOTICE_MISSING",
            ScanJurisdiction.UK,
            FindingSeverity.HIGH,
            FindingCategory.DOCUMENTS,
            "No privacy notice found",
            null,
            "UK GDPR Art. 13–14; Data Protection Act 2018",
            "Where a controller collects personal data, UK GDPR requires a transparent privacy notice. "
                    + "No privacy policy / data-protection notice could be found in the site links or text.",
            "1. Publish a UK GDPR privacy notice on a stable URL. 2. Link it in the footer and next to "
                    + "data collection forms. 3. Cover controller identity, purposes, lawful basis, retention, "
                    + "recipients, transfers and data-subject rights, plus the right to complain to the ICO.",
            "Privacy notice found",
            "A privacy notice / data-protection statement was found on the site.");

    @Override
    public Set<JurisdictionLayer> supportedLayers() {
        return Set.of(JurisdictionLayer.UK);
    }

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        if (pages.isEmpty() || GdprPatterns.hasPrivacyNotice(ctx)) {
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
                VerificationStatus.UNVERIFIED,
                "PRIVACY_NOTICE_ABSENT",
                java.util.Map.of()));
    }
}
