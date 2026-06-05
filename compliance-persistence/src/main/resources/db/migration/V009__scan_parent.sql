-- Этап 2 §2.2 — связь повторных проверок (re-scan)

-- ON DELETE SET NULL: удаление родительского скана (напр. TTL-чистка гостевых) не должно
-- ронять дочерний из-за self-FK — связь просто обнуляется.
ALTER TABLE compliance_scans
    ADD COLUMN parent_scan_id UUID REFERENCES compliance_scans(id) ON DELETE SET NULL;

CREATE INDEX idx_compliance_scans_parent ON compliance_scans(parent_scan_id);
