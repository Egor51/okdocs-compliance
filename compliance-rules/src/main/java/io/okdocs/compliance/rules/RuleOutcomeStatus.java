package io.okdocs.compliance.rules;

/** Итог выполнения правила на скане: нарушение, успешная проверка или невозможность оценить. */
public enum RuleOutcomeStatus {
    PASSED,
    FAILED,
    NOT_EVALUATED
}
