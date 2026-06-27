package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class FindingAssemblerTest {

    private static final RuleDefinition DEF = new RuleDefinition(
            "NO_PRIVACY_POLICY", ScanJurisdiction.RU, FindingSeverity.HIGH, FindingCategory.DOCUMENTS,
            "Нет политики", "10 000 ₽", "ст. 18.1 152-ФЗ", "объяснение", "рекомендация");

    private RuleMetadataResolver resolver;
    private FindingAssembler assembler;

    @BeforeEach
    void setUp() {
        resolver = Mockito.mock(RuleMetadataResolver.class);
        // Реальный рендерер: legacy-facts (evidenceKey==null) → plain fact.evidence(), поведение то же.
        assembler = new FindingAssembler(resolver, new EvidenceRenderer());
    }

    private static RuleFact fact(String code) {
        return new RuleFact(code, "evidence-text", "https://site.ru/page", SourceType.HTML,
                EvidenceType.STATIC_ANALYSIS, 0.85, "signal=x", VerificationStatus.DETECTED);
    }

    @Test
    void appliesDefinitionMetadataAndFactObservation() {
        when(resolver.resolve("NO_PRIVACY_POLICY", ScanJurisdiction.RU)).thenReturn(Optional.of(DEF));
        UUID scanId = UUID.randomUUID();

        List<ComplianceFinding> findings =
                assembler.assemble(scanId, ScanJurisdiction.RU, "ru", List.of(fact("NO_PRIVACY_POLICY")));

        assertThat(findings).hasSize(1);
        ComplianceFinding f = findings.get(0);
        // Классификация — из definition
        assertThat(f.getScanId()).isEqualTo(scanId);
        assertThat(f.getCode()).isEqualTo("NO_PRIVACY_POLICY");
        assertThat(f.getSeverity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(f.getCategory()).isEqualTo(FindingCategory.DOCUMENTS);
        assertThat(f.getTitle()).isEqualTo("Нет политики");
        assertThat(f.getFineAmount()).isEqualTo("10 000 ₽");
        assertThat(f.getLegalBasis()).isEqualTo("ст. 18.1 152-ФЗ");
        // Наблюдение — из факта
        assertThat(f.getEvidence()).isEqualTo("evidence-text");
        assertThat(f.getSourceUrl()).isEqualTo("https://site.ru/page");
        assertThat(f.getPageUrl()).isEqualTo("https://site.ru/page");
        assertThat(f.getSourceType()).isEqualTo(SourceType.HTML);
        assertThat(f.getEvidenceType()).isEqualTo(EvidenceType.STATIC_ANALYSIS);
        assertThat(f.getConfidence()).isEqualTo(0.85);
        assertThat(f.getVerificationStatus()).isEqualTo(VerificationStatus.DETECTED);
        assertThat(f.getMatchedSignals()).isEqualTo("signal=x");
    }

    @Test
    void dropsFactForUnknownRuleCode() {
        // Код, которого нет в коде (мёртвый факт) — резолвер возвращает empty.
        when(resolver.resolve("GHOST_RULE", ScanJurisdiction.RU)).thenReturn(Optional.empty());
        List<ComplianceFinding> findings =
                assembler.assemble(UUID.randomUUID(), ScanJurisdiction.RU, "ru", List.of(fact("GHOST_RULE")));
        assertThat(findings).isEmpty();
    }

    @Test
    void dropsFactNotResolvableForJurisdiction() {
        // Правило не объявлено ни в одном слое юрисдикции скана → empty → факт отбрасывается.
        when(resolver.resolve("NO_PRIVACY_POLICY", ScanJurisdiction.EU)).thenReturn(Optional.empty());
        List<ComplianceFinding> findings =
                assembler.assemble(UUID.randomUUID(), ScanJurisdiction.EU, "ru", List.of(fact("NO_PRIVACY_POLICY")));
        assertThat(findings).isEmpty();
    }

    @Test
    void emptyFactsGiveEmptyFindings() {
        assertThat(assembler.assemble(UUID.randomUUID(), ScanJurisdiction.RU, "ru", List.of())).isEmpty();
    }

    @Test
    void structuredEvidenceRenderedPerLocale() {
        // Мигрированный факт (evidenceKey+params) рендерится по locale: en → английский evidence,
        // ru → русский. Это сквозная проверка Этапа 3 (detector→RuleFact→renderer→finding).
        var def = new RuleDefinition("MISSING_HSTS", ScanJurisdiction.EU, FindingSeverity.MEDIUM,
                FindingCategory.SECURITY, "t", null, "GDPR Art. 32", null, null);
        when(resolver.resolve("MISSING_HSTS", ScanJurisdiction.DE)).thenReturn(Optional.of(def));
        var structuredFact = new RuleFact("MISSING_HSTS", "PLAIN-RU-FALLBACK", "https://s/p",
                SourceType.HTTP_HEADER, EvidenceType.STATIC_ANALYSIS, 0.95, null,
                VerificationStatus.DETECTED, "MISSING_HSTS", java.util.Map.of("page", "https://s/p"));

        var en = assembler.assemble(UUID.randomUUID(), ScanJurisdiction.DE, "en", List.of(structuredFact));
        assertThat(en).singleElement().satisfies(f ->
                assertThat(f.getEvidence())
                        .isEqualTo("The Strict-Transport-Security header is missing on https://s/p."));

        var ru = assembler.assemble(UUID.randomUUID(), ScanJurisdiction.DE, "ru", List.of(structuredFact));
        assertThat(ru).singleElement().satisfies(f ->
                assertThat(f.getEvidence())
                        .isEqualTo("На странице https://s/p отсутствует заголовок Strict-Transport-Security."));
    }
}
