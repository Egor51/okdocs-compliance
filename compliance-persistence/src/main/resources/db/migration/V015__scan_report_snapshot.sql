-- Перенос сборки отчёта из API в worker: worker строит готовый ScanReportResponse-снапшот (premium +
-- free JSON) в той же транзакции, что findings/status/outbox; API при getReport отдаёт snapshot
-- passthrough-ом по effectiveTier, без доменной логики.
--
-- TEXT, а не JSONB — для консистентности с diagnostics_json (тоже TEXT) и чтобы не тянуть Hibernate
-- JSON-маппинг; API всё равно отдаёт строку passthrough-ом. Если позже понадобятся JSON-запросы по
-- содержимому — мигрируем на JSONB отдельно.
--
-- report_schema_version: snapshot фиксирует JSON на момент скана; через месяц DTO может измениться.
-- Версия даёт понятную миграцию/compat-path при чтении старых снапшотов.
--
-- ON DELETE CASCADE: TTL-чистка FREE_MARKETING (deleteByKindOlderThan) подхватит snapshot автоматически.

CREATE TABLE compliance_scan_reports (
    scan_id               UUID PRIMARY KEY REFERENCES compliance_scans(id) ON DELETE CASCADE,
    report_schema_version INT NOT NULL DEFAULT 1,
    premium_report_json   TEXT NOT NULL,
    free_report_json      TEXT NOT NULL,
    created_at            TIMESTAMP NOT NULL DEFAULT now()
);
