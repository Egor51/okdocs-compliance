package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;

/**
 * Наблюдение правила о конкретной находке (НЕ классификация).
 * <p>
 * severity здесь намеренно НЕТ: {@code RuleFact} отвечает «что правило обнаружило», а
 * классификация (severity, штраф, тексты) — это {@link RuleDefinition} + override из БД,
 * накладывается в {@code FindingAssembler} (worker). Поля факта = ровно то, что знает только
 * само правило про находку и что assembler не восстановит: где найдено, как обнаружено,
 * насколько уверенно, доказательство.
 */
public record RuleFact(
        String code,
        String evidence,
        String sourceUrl,
        SourceType sourceType,
        EvidenceType evidenceType,
        Double confidence,
        String matchedSignals,
        VerificationStatus verificationStatus
) {
}
