package io.okdocs.compliance.rules;

import java.util.Map;

/** Результат проверки доступности входных данных с конкретной причиной NOT_EVALUATED. */
public record RuleApplicability(
        boolean applicable,
        String message,
        String messageKey,
        Map<String, Object> messageParams
) {
    public RuleApplicability {
        messageParams = messageParams == null ? Map.of() : Map.copyOf(messageParams);
    }

    public static RuleApplicability available() {
        return new RuleApplicability(true, null, null, Map.of());
    }

    public static RuleApplicability unavailable(String message, String messageKey) {
        return new RuleApplicability(false, message, messageKey, Map.of());
    }
}
