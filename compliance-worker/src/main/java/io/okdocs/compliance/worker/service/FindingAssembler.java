package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Превращает {@link RuleFact} (наблюдение правила) в {@link ComplianceFinding}, накладывая
 * метаданные из {@link RuleDefinition} через {@link RuleMetadataResolver} (§5.5). Единственная
 * точка слияния «дефолт из кода (+ БД-override, отложено)» — {@code compliance-rules} остаётся
 * без БД.
 * <p>
 * <b>Severity берётся здесь</b> (из definition), не из факта — {@code RuleFact} severity не несёт
 * (§3.1). Факты правил, которых нет в коде / выключенных, отбрасываются.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FindingAssembler {

    private final RuleMetadataResolver metadataResolver;
    private final EvidenceRenderer evidenceRenderer;

    public List<ComplianceFinding> assemble(UUID scanId, ScanJurisdiction jurisdiction, String locale,
                                            List<RuleFact> facts) {
        List<ComplianceFinding> findings = new ArrayList<>();
        for (RuleFact fact : facts) {
            // Метаданные резолвятся под юрисдикцию скана: DE-скан получает DE-метаданные с
            // fallback на EU-baseline (§ multi-layer). Без юрисдикции DE/FR/ES findings остались
            // бы без локального legal-контекста.
            Optional<RuleDefinition> definition = metadataResolver.resolve(fact.code(), jurisdiction);
            if (definition.isEmpty()) {
                log.debug("Dropping fact for unknown/disabled rule code={} jurisdiction={}",
                        fact.code(), jurisdiction);
                continue;
            }
            // Evidence локализуется по locale пользователя (§ Этап 2): structured-key+params →
            // EvidenceRenderer; немигрированные правила (key==null) → fallback на plain fact.evidence().
            String evidence = evidenceRenderer.render(fact, locale);
            findings.add(toFinding(scanId, fact, definition.get(), evidence));
        }
        return findings;
    }

    private static ComplianceFinding toFinding(UUID scanId, RuleFact fact, RuleDefinition def,
                                               String evidence) {
        ComplianceFinding finding = new ComplianceFinding();
        finding.setScanId(scanId);
        finding.setCode(def.code());
        // Классификация — из definition (severity/category/тексты/штраф/основание).
        finding.setSeverity(def.severity());
        finding.setCategory(def.category());
        finding.setTitle(def.title());
        finding.setFineAmount(def.fineAmount());
        finding.setLegalBasis(def.legalBasis());
        finding.setExplanation(def.explanation());
        finding.setRecommendation(def.recommendation());
        // Наблюдение — из факта (где/как/насколько уверенно). evidence уже отрендерен по locale.
        finding.setEvidence(evidence);
        finding.setSourceUrl(fact.sourceUrl());
        finding.setPageUrl(fact.sourceUrl());
        finding.setSourceType(fact.sourceType());
        finding.setEvidenceType(fact.evidenceType());
        finding.setConfidence(fact.confidence());
        finding.setVerificationStatus(fact.verificationStatus());
        finding.setMatchedSignals(fact.matchedSignals());
        return finding;
    }
}
