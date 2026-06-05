/**
 * Фоновые задачи compliance-worker: ScanReaper, scheduled worker jobs, task dispatchers.
 * Outbox relay живёт в compliance-messaging, а не здесь.
 * Сканируется как ComplianceWorkerApplication, так и combined ComplianceApplication.
 */
package io.okdocs.compliance.worker.job;
