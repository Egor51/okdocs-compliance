package io.okdocs.compliance.contracts.crawler;

/**
 * Метрики обхода. {@code pagesFetched} — единый источник для {@code pagesScanned} в скане и событии.
 * Намеренно НЕ содержит {@code ruleErrors} — те рождаются в движке правил, не в краулере;
 * объединение в {@code DiagnosticsDto} происходит на уровне воркера.
 */
public record CrawlerDiagnostics(
        int pagesAttempted,
        int pagesFetched,
        int pagesFailed,
        boolean crawlerTimedOut
) {
}
