package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;

import java.util.Map;

/**
 * Наблюдение правила о конкретной находке (НЕ классификация).
 * <p>
 * severity здесь намеренно НЕТ: {@code RuleFact} отвечает «что правило обнаружило», а
 * классификация (severity, штраф, тексты) — это {@link RuleDefinition} + override из БД,
 * накладывается в {@code FindingAssembler} (worker). Поля факта = ровно то, что знает только
 * само правило про находку и что assembler не восстановит: где найдено, как обнаружено,
 * насколько уверенно, доказательство.
 * <p>
 * <b>Structured evidence (§ PLAN-evidence-localization, Этап 2):</b> {@code evidence} — готовый
 * текст (legacy, обычно RU), идёт мимо локализации. {@code evidenceKey} + {@code params} —
 * структурное наблюдение для локализуемого рендера в worker ({@code EvidenceRenderer}) по locale
 * пользователя. Детектор НЕ знает locale и текст НЕ собирает — отдаёт key+данные. Backward-compat:
 * пока правило не мигрировано, {@code evidenceKey == null} → рендерер использует {@code evidence}.
 */
public record RuleFact(
        String code,
        String evidence,
        String sourceUrl,
        SourceType sourceType,
        EvidenceType evidenceType,
        Double confidence,
        String matchedSignals,
        VerificationStatus verificationStatus,
        /** Ключ шаблона локализуемого evidence (напр. {@code MISSING_HEADER}); null → legacy plain. */
        String evidenceKey,
        /** Параметры шаблона (page/cookieNames/header/daysLeft…). JSON-friendly значения. */
        Map<String, Object> params
) {
    public RuleFact {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /**
     * Legacy-конструктор (8-арг): только plain {@code evidence}, без structured-ключа. Сохранён
     * совместимым — все существующие детекторы и тесты используют его без изменений.
     */
    public RuleFact(String code, String evidence, String sourceUrl, SourceType sourceType,
                    EvidenceType evidenceType, Double confidence, String matchedSignals,
                    VerificationStatus verificationStatus) {
        this(code, evidence, sourceUrl, sourceType, evidenceType, confidence, matchedSignals,
                verificationStatus, null, Map.of());
    }
}
