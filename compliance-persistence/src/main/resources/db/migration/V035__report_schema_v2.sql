-- Report schema v2: структурированные санкционные сценарии и арифметический диапазон штрафов.
-- Существующие snapshot остаются v1; default применяется только к новым строкам без явного значения.
ALTER TABLE compliance_scan_reports ALTER COLUMN report_schema_version SET DEFAULT 2;
