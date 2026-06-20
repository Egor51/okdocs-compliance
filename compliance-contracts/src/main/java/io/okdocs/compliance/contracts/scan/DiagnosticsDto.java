package io.okdocs.compliance.contracts.scan;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Диагностика скана в отчёте — слияние двух источников: метрики fetch'а краулера
 * ({@code pagesAttempted/Fetched/Failed}, {@code crawlerTimedOut}) и ошибки анализа правил
 * ({@code ruleErrors}). Объединяется на уровне воркера, не в краулерной модели.
 */
public record DiagnosticsDto(
        int pagesAttempted,
        int pagesFetched,
        int pagesFailed,
        boolean crawlerTimedOut,
        int priorityHintsAttempted,
        int priorityHintsMissed,
        List<String> ruleErrors,
        List<RuleOutcomeDto> ruleOutcomes
) {
    public DiagnosticsDto {
        ruleErrors = ruleErrors == null ? List.of() : List.copyOf(ruleErrors);
        ruleOutcomes = ruleOutcomes == null ? List.of() : List.copyOf(ruleOutcomes);
    }

    public DiagnosticsDto(int pagesAttempted, int pagesFetched, int pagesFailed,
                          boolean crawlerTimedOut, List<String> ruleErrors) {
        this(pagesAttempted, pagesFetched, pagesFailed, crawlerTimedOut, 0, 0, ruleErrors, List.of());
    }

    public DiagnosticsDto(int pagesAttempted, int pagesFetched, int pagesFailed,
                          boolean crawlerTimedOut, List<String> ruleErrors,
                          List<RuleOutcomeDto> ruleOutcomes) {
        this(pagesAttempted, pagesFetched, pagesFailed, crawlerTimedOut, 0, 0, ruleErrors, ruleOutcomes);
    }

    @Override
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public List<RuleOutcomeDto> ruleOutcomes() {
        return ruleOutcomes;
    }
}
