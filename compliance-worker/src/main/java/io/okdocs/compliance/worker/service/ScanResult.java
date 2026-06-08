package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.persistence.scan.ComplianceFinding;

import java.util.List;

/**
 * Итог анализа для финального перехода статуса (§5.2/5.3): собранные findings, рассчитанный score,
 * число обработанных страниц и сериализованная диагностика (слияние метрик краулера и ошибок
 * правил). Передаётся из {@link io.okdocs.compliance.worker.job.ScanRequestedListener} в
 * {@link ScanLifecycleService#complete}/{@code partial}.
 */
public record ScanResult(
        List<ComplianceFinding> findings,
        Integer score,
        int pagesFetched,
        String diagnosticsJson
) {
}
