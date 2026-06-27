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
 * Non-essential consent toggles are pre-checked / default-on in the cookie banner. Under GDPR consent
 * must be an affirmative act (Art. 4(11), Recital 32) — pre-ticked boxes or default-on switches do not
 * constitute valid consent (CJEU Planet49). Consent-scenario rule (Фаза 4 data): reads
 * {@link ConsentBannerInfo#precheckedToggles()}. EU baseline (layer {@code EU}).
 */
public final class EuConsentPrecheckedRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "EU_CONSENT_PRECHECKED_OR_DEFAULT_ON",
            ScanJurisdiction.EU,
            FindingSeverity.HIGH,
            FindingCategory.COOKIES,
            "Consent toggles are pre-checked or default-on",
            null,
            "GDPR Art. 4(11), Recital 32 (affirmative act); CJEU C-673/17 Planet49; ePrivacy Art. 5(3)",
            "Non-essential cookie/consent toggles in the banner are pre-checked or switched on by "
                    + "default. Consent must be a clear affirmative action — pre-ticked boxes or default-on "
                    + "switches do not constitute valid consent.",
            "Set all non-essential consent toggles to off by default; require the user to actively "
                    + "opt in. Only strictly necessary categories may be on (and need no consent).",
            "Consent toggles are off by default",
            "No pre-checked or default-on non-essential consent toggles were detected.");

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
            if (!banner.bannerFound() || !banner.precheckedToggles()) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Non-essential consent toggles are pre-checked / default-on in the cookie banner."
                            + (banner.cmpProvider() != null ? " CMP: " + banner.cmpProvider() + "." : ""),
                    page.url(),
                    SourceType.HTML,
                    EvidenceType.DYNAMIC_RENDER,
                    0.80,
                    "prechecked-consent-toggles",
                    VerificationStatus.DETECTED));
        }
        return facts;
    }
}
