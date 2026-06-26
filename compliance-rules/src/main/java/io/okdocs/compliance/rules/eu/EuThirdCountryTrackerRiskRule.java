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
import io.okdocs.compliance.rules.common.TrackerCatalog;
import io.okdocs.compliance.rules.common.TrackerCatalog.TrackerInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Third-country trackers are loaded on the site. Most major analytics/advertising providers are
 * established outside the EU/EEA (US, CN), so loading them transfers personal data to a third country
 * under GDPR Chapter V — requiring a valid transfer mechanism and disclosure. Static detection: match
 * external script/style domains against {@link TrackerCatalog}, keep those whose provider is in a
 * third country. EU baseline rule (layer {@code EU}). Pre/post-consent timing is a separate Phase-5
 * consent rule; this one flags the transfer-risk presence regardless of consent.
 */
public final class EuThirdCountryTrackerRiskRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "EU_THIRD_COUNTRY_TRACKER_RISK",
            ScanJurisdiction.EU,
            FindingSeverity.MEDIUM,
            FindingCategory.TRACKERS,
            "Third-country trackers transfer personal data outside the EU/EEA",
            null,
            "GDPR Chapter V, Art. 44–49 (transfers to third countries)",
            "The site loads trackers whose providers are established outside the EU/EEA. Loading them "
                    + "transfers personal data (IP address, identifiers) to a third country, which requires a "
                    + "valid transfer mechanism (e.g. adequacy decision, SCCs) and disclosure in the privacy "
                    + "notice.",
            "1. Inventory third-country trackers and the data they receive. 2. Ensure a valid transfer "
                    + "mechanism (adequacy / SCCs / DPF). 3. Disclose the transfers and recipients in the "
                    + "privacy notice. 4. Load non-essential trackers only after consent.",
            "No third-country tracker transfer risk detected",
            "No trackers from providers outside the EU/EEA were detected on the scanned pages.");

    @Override
    public java.util.Set<JurisdictionLayer> supportedLayers() {
        return java.util.Set.of(JurisdictionLayer.EU);
    }

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        List<RuleFact> facts = new ArrayList<>();

        for (PageAnalysisResult p : pages) {
            // domain -> "Provider (CC)" — дедуп по провайдеру, порядок обнаружения сохранён.
            Map<String, String> thirdCountry = new LinkedHashMap<>();
            collect(p.externalScriptDomains(), thirdCountry);
            collect(p.externalStyleDomains(), thirdCountry);
            if (thirdCountry.isEmpty()) {
                continue;
            }
            String descr = thirdCountry.values().stream().distinct().collect(Collectors.joining(", "));
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Third-country trackers loaded: " + descr
                            + ". These transfer personal data outside the EU/EEA.",
                    p.url(),
                    SourceType.HTML,
                    RuleSupport.evidenceType(ctx),
                    0.85,
                    String.join(",", thirdCountry.keySet()),
                    VerificationStatus.DETECTED));
        }
        return facts;
    }

    private static void collect(List<String> domains, Map<String, String> out) {
        if (domains == null) {
            return;
        }
        for (String domain : domains) {
            TrackerCatalog.lookup(domain)
                    .filter(TrackerCatalog::isThirdCountry)
                    .ifPresent(info -> out.put(info.domain(), label(info)));
        }
    }

    private static String label(TrackerInfo info) {
        return info.provider() + " (" + info.country() + ")";
    }
}
