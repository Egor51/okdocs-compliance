-- Этап 2 §2.2 — связь повторных проверок (re-scan)

ALTER TABLE compliance_scans
    ADD COLUMN parent_scan_id UUID REFERENCES compliance_scans(id);

CREATE INDEX idx_compliance_scans_parent ON compliance_scans(parent_scan_id);
