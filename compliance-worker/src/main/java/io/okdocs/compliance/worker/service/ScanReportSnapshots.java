package io.okdocs.compliance.worker.service;

/**
 * Два сериализованных {@code ScanReportResponse}: полный premium и FREE-маскированный. Оба
 * с {@code paywallCta = null} — product-shell CTA дописывает API при выдаче FREE. Сохраняются
 * в {@code compliance_scan_reports} в той же транзакции, что findings/status/outbox.
 */
public record ScanReportSnapshots(String premiumJson, String freeJson) {
}
