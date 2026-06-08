-- Этап 5.5 — split FREE/PREMIUM flow: режим выполнения скана в БД (source of truth для worker'а).
--
-- scan_kind       — продуктовый flow (FREE_MARKETING / CABINET_PREMIUM); worker гейтит по нему.
-- max_pages       — лимит страниц краула, перенесён из ScanRequestedEvent в БД (worker не зависит
--                   от producer-решений, кроме scanId).
-- dynamic_required — для CABINET_PREMIUM dynamic обязателен: CDP недоступен → FAILED + refund.
--
-- DEFAULT 'CABINET_PREMIUM'/false безопасен: новый функционал, существующих строк с другим
-- режимом нет. max_pages DEFAULT 1 (консервативно); API проставляет реальное значение при создании.

ALTER TABLE compliance_scans ADD COLUMN scan_kind VARCHAR(30) NOT NULL DEFAULT 'CABINET_PREMIUM';
ALTER TABLE compliance_scans ADD COLUMN max_pages INT NOT NULL DEFAULT 1;
ALTER TABLE compliance_scans ADD COLUMN dynamic_required BOOLEAN NOT NULL DEFAULT false;
