-- Reaper зависших сканов (§5.3) выбирает по (status IN (CRAWLING, ANALYZING) AND updated_at < cutoff).
-- Существующий idx_compliance_scans_status_created покрывает (status, created_at), но reaper
-- фильтрует по updated_at — без этого индекса выборка деградирует в seq scan при росте таблицы.
CREATE INDEX IF NOT EXISTS idx_compliance_scans_status_updated
    ON compliance_scans (status, updated_at);
