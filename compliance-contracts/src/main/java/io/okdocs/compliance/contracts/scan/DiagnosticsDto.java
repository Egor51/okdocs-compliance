package io.okdocs.compliance.contracts.scan;

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
        List<String> ruleErrors
) {
}
