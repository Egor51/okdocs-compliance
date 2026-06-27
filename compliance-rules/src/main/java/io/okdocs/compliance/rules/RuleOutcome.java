package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;

import java.util.Map;

/**
 * Сводный outcome правила для отчёта и re-scan diff, без page-level доказательств.
 * <p>
 * {@code message} — legacy plain-текст причины (обычно RU, для NOT_EVALUATED). {@code messageKey} +
 * {@code messageParams} — структурный вариант для локализуемого рендера в worker по locale
 * пользователя (§ PLAN-evidence-localization, Этап 4). Backward-compat: {@code messageKey == null} →
 * рендерер использует plain {@code message}.
 */
public record RuleOutcome(
        String code,
        RuleOutcomeStatus status,
        String title,
        FindingSeverity severity,
        FindingCategory category,
        String message,
        String positiveTitle,
        String positiveMessage,
        /** Ключ шаблона локализуемого message (NOT_EVALUATED-причина); null → legacy plain. */
        String messageKey,
        /** Параметры шаблона message (обычно пусто — причины статичны). */
        Map<String, Object> messageParams
) {
    public RuleOutcome {
        messageParams = messageParams == null ? Map.of() : Map.copyOf(messageParams);
    }

    /** 8-арг legacy: с positive*, но без structured message-ключа. */
    public RuleOutcome(String code, RuleOutcomeStatus status, String title, FindingSeverity severity,
                       FindingCategory category, String message, String positiveTitle,
                       String positiveMessage) {
        this(code, status, title, severity, category, message, positiveTitle, positiveMessage,
                null, Map.of());
    }

    /** 6-арг legacy: без positive* и без structured message-ключа. */
    public RuleOutcome(String code, RuleOutcomeStatus status, String title, FindingSeverity severity,
                       FindingCategory category, String message) {
        this(code, status, title, severity, category, message, null, null, null, Map.of());
    }

    /** NOT_EVALUATED с messageKey: статическая причина для локализуемого рендера + plain fallback. */
    public RuleOutcome(String code, RuleOutcomeStatus status, String title, FindingSeverity severity,
                       FindingCategory category, String message, String messageKey) {
        this(code, status, title, severity, category, message, null, null, messageKey, Map.of());
    }
}
