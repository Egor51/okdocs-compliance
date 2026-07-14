package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.crawler.FormInfo;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.FormPurpose;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnprotectedDataFormsRuleTest {

    private final UnprotectedDataFormsRule rule = new UnprotectedDataFormsRule();

    @Test
    void isCritical() {
        assertThat(rule.definition().severity()).isEqualTo(FindingSeverity.CRITICAL);
        assertThat(rule.definition().code()).isEqualTo("UNPROTECTED_DATA_FORMS");
    }

    @Test
    void flagsDataFormWithoutConsentWithStaticConfidence() {
        ScanAnalysisContext ctx = TestFixtures.ctx(
                TestFixtures.simplePage("https://site.ru/p", TestFixtures.dataFormNoConsent("/lead")));

        List<RuleFact> facts = rule.evaluate(ctx);

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.sourceUrl()).isEqualTo("https://site.ru/p");
            assertThat(f.confidence()).isEqualTo(0.80);
            assertThat(f.evidenceType()).isEqualTo(EvidenceType.STATIC_ANALYSIS);
        });
    }

    @Test
    void silentWhenConsentTextPresent() {
        ScanAnalysisContext ctx = TestFixtures.ctx(
                TestFixtures.simplePage("https://site.ru", TestFixtures.dataFormWithConsent("/lead")));

        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void silentWhenFormCollectsNoData() {
        ScanAnalysisContext ctx = TestFixtures.ctx(
                TestFixtures.simplePage("https://site.ru", TestFixtures.emptyForm("/search")));

        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void emitsOneFactPerPageNotPerForm() {
        // okdocks-логика: один finding на страницу с непокрытой формой (не на каждую форму).
        ScanAnalysisContext ctx = TestFixtures.ctx(
                TestFixtures.simplePage("https://site.ru",
                        TestFixtures.dataFormNoConsent("/a"),
                        TestFixtures.dataFormNoConsent("/b")));

        assertThat(rule.evaluate(ctx)).hasSize(1);
    }

    @Test
    void ignoresLoginFormWithoutConsentCheckbox() {
        FormInfo login = new FormInfo("", "POST", List.of("email", "password"),
                true, false, false, false, false, false, true, FormPurpose.AUTH_LOGIN);
        ScanAnalysisContext ctx = TestFixtures.ctx(
                TestFixtures.simplePage("https://site.ru/login", login));

        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void keepsUnknownDataFormAsUnverifiedManualCheck() {
        FormInfo unknown = new FormInfo("", "POST", List.of("email"),
                false, false, false, false, false, false, true, FormPurpose.UNKNOWN);
        ScanAnalysisContext ctx = TestFixtures.ctx(
                TestFixtures.simplePage("https://site.ru/custom", unknown));

        assertThat(rule.evaluate(ctx)).singleElement().satisfies(f -> {
            assertThat(f.verificationStatus().name()).isEqualTo("UNVERIFIED");
            assertThat(f.evidence()).contains("purpose=UNKNOWN", "JavaScript handler");
        });
    }
}
