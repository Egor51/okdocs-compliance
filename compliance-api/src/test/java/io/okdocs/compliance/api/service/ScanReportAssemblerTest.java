package io.okdocs.compliance.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScanReportAssemblerTest {

    private final ScanReportAssembler assembler = new ScanReportAssembler(
            new ComplianceApiProperties(null, null, null, null,
                    new ComplianceApiProperties.PaywallCta("pay", "text", "/payment"),
                    null, null),
            new ObjectMapper());

    @Test
    void cabinetPremiumUsesEffectivePremiumTierAndDeduplicatesFindings() {
        ComplianceScan scan = scan(ScanKind.CABINET_PREMIUM, ScanTier.FREE);

        var response = assembler.assemble(scan, List.of(
                finding("THIRD_PARTY_TRACKERS", FindingSeverity.MEDIUM, 0.70,
                        "https://site.ru/a", "tracker-a", VerificationStatus.DETECTED),
                finding("THIRD_PARTY_TRACKERS", FindingSeverity.MEDIUM, 0.90,
                        "https://site.ru/b", "tracker-b", VerificationStatus.UNVERIFIED),
                finding("RKN_REGISTRY_NOT_VERIFIED", FindingSeverity.HIGH, null,
                        "https://site.ru", "rkn", VerificationStatus.UNVERIFIED)));

        assertThat(response.tier()).isEqualTo(ScanTier.PREMIUM);
        assertThat(response.paywallCta()).isNull();
        assertThat(response.durationMs()).isEqualTo(12_345L);
        assertThat(response.findings()).hasSize(2);
        assertThat(response.summary().medium()).isEqualTo(1);
        assertThat(response.summary().high()).isEqualTo(1);

        var tracker = response.findings().get(0);
        assertThat(tracker.code()).isEqualTo("THIRD_PARTY_TRACKERS");
        assertThat(tracker.sourceUrl()).isEqualTo("https://site.ru/b");
        assertThat(tracker.evidence()).isEqualTo("evidence tracker-b");
        assertThat(tracker.matchedSignals()).containsExactly("tracker-b");
    }

    @Test
    void freeMarketingKeepsPaywallAndMasksPremiumFields() {
        ComplianceScan scan = scan(ScanKind.FREE_MARKETING, ScanTier.FREE);

        var response = assembler.assemble(scan, List.of(
                finding("THIRD_PARTY_TRACKERS", FindingSeverity.MEDIUM, 0.70,
                        "https://site.ru/a", "tracker-a", VerificationStatus.DETECTED)));

        assertThat(response.tier()).isEqualTo(ScanTier.FREE);
        assertThat(response.paywallCta()).isNotNull();
        assertThat(response.findings()).hasSize(1);
        assertThat(response.findings().get(0).evidence()).isNull();
        assertThat(response.findings().get(0).sourceUrl()).isNull();
        assertThat(response.findings().get(0).matchedSignals()).isNull();
    }

    private static ComplianceScan scan(ScanKind kind, ScanTier tier) {
        ComplianceScan scan = new ComplianceScan();
        scan.setId(UUID.randomUUID());
        scan.setSiteUrl("https://site.ru");
        scan.setSiteDomain("site.ru");
        scan.setStatus(ScanStatus.COMPLETED);
        scan.setScore(80);
        scan.setKind(kind);
        scan.setTier(tier);
        scan.setStartedAt(Instant.parse("2026-06-08T20:00:00Z"));
        scan.setFinishedAt(Instant.parse("2026-06-08T20:00:12.345Z"));
        return scan;
    }

    private static ComplianceFinding finding(String code, FindingSeverity severity, Double confidence,
                                             String sourceUrl, String signal,
                                             VerificationStatus verificationStatus) {
        ComplianceFinding finding = new ComplianceFinding();
        finding.setCode(code);
        finding.setSeverity(severity);
        finding.setCategory(FindingCategory.TRACKERS);
        finding.setTitle(code);
        finding.setFineAmount("fine");
        finding.setLegalBasis("law");
        finding.setExplanation("explanation");
        finding.setRecommendation("recommendation");
        finding.setEvidence("evidence " + signal);
        finding.setSourceUrl(sourceUrl);
        finding.setSourceType(SourceType.HTML);
        finding.setConfidence(confidence);
        finding.setVerificationStatus(verificationStatus);
        finding.setEvidenceType(EvidenceType.DYNAMIC_RENDER);
        finding.setMatchedSignals(signal);
        return finding;
    }
}
