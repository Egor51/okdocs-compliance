package io.okdocs.compliance.contracts.crawler;

/**
 * Конкретная причина, по которой consent-сценарий нельзя завершить достоверным результатом.
 * Причина хранится в отчёте и не должна подменяться общим «нет входных данных».
 */
public enum ConsentScenarioFailureReason {
    NONE,
    SCENARIO_DISABLED,
    BANNER_NOT_FOUND,
    REJECT_NOT_FOUND,
    REJECT_CLICK_FAILED,
    POST_REJECT_CAPTURE_FAILED,
    TIMEOUT,
    CDP_ERROR
}
