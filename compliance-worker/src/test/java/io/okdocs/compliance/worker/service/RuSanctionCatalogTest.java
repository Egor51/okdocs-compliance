package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuSanctionCatalogTest {

    @Test
    void sumsDistinctViolationGroupsWithoutDoubleCountingAlternatives() {
        var exposure = RuSanctionCatalog.exposure(List.of(
                finding("HOSTING_OUTSIDE_RU_DETECTED", VerificationStatus.DETECTED),
                finding("NO_PRIVACY_POLICY", VerificationStatus.CONFIRMED)));

        assertThat(exposure.minimumRelevantAmount()).isEqualTo(1_010_000);
        assertThat(exposure.maximumRelevantAmount()).isEqualTo(18_060_000);
        assertThat(RuSanctionCatalog.rangeLabel(exposure))
                .isEqualTo("от 1 010 000 до 18 060 000 ₽");
        assertThat(exposure.headline()).isEqualTo(
                "От 1 010 000 до 18 060 000 ₽ — суммарно по потенциальным нарушениям");
        assertThat(exposure.calculationMethod()).isEqualTo("SUM_DISTINCT_VIOLATION_GROUP_RANGES");
        assertThat(exposure.scenariosAreNotSummed()).isFalse();
        assertThat(exposure.scenarios())
                .extracting(s -> s.maximumAmount())
                .contains(18_000_000L, 6_000_000L, 60_000L, 20_000L);
        assertThat(exposure.maximumRelevantAmount()).isLessThan(
                exposure.scenarios().stream().mapToLong(s -> s.maximumAmount()).sum());
        assertThat(exposure.scenarios())
                .extracting(s -> s.aggregationGroup())
                .containsOnly("DATA_LOCALIZATION", "PRIVACY_POLICY_PUBLICATION");
    }

    @Test
    void generalProcessingRiskShowsFirstAndRepeatedScenarios() {
        var exposure = RuSanctionCatalog.exposure(List.of(
                finding("POSSIBLE_TRACKERS_BEFORE_CONSENT", VerificationStatus.DETECTED),
                finding("THIRD_PARTY_TRACKERS", VerificationStatus.DETECTED)));

        assertThat(exposure.minimumRelevantAmount()).isEqualTo(150_000);
        assertThat(exposure.maximumRelevantAmount()).isEqualTo(500_000);
        assertThat(RuSanctionCatalog.rangeLabel(exposure))
                .isEqualTo("от 150 000 до 500 000 ₽");
        assertThat(exposure.scenarios()).hasSize(2);
        assertThat(exposure.scenarios().get(0).relatedFindingCodes())
                .containsExactly("POSSIBLE_TRACKERS_BEFORE_CONSENT", "THIRD_PARTY_TRACKERS");
    }

    @Test
    void unverifiedSignalsDoNotCreateMarketingFine() {
        assertThat(RuSanctionCatalog.exposure(List.of(
                finding("HOSTING_OUTSIDE_RU_DETECTED", VerificationStatus.UNVERIFIED)))).isNull();
    }

    @Test
    void technicalFindingWithoutDirectSanctionDoesNotCreateExposure() {
        assertThat(RuSanctionCatalog.exposure(List.of(
                finding("MISSING_HSTS", VerificationStatus.DETECTED)))).isNull();
    }

    private static ComplianceFinding finding(String code, VerificationStatus status) {
        ComplianceFinding finding = new ComplianceFinding();
        finding.setCode(code);
        finding.setVerificationStatus(status);
        finding.setSeverity(FindingSeverity.HIGH);
        return finding;
    }
}
