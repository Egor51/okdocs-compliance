-- Structured, reportable terminal scan failures. All columns stay nullable for legacy rows.

ALTER TABLE compliance_scans
    ADD COLUMN failure_code VARCHAR(50),
    ADD COLUMN failure_stage VARCHAR(30),
    ADD COLUMN failure_retryable BOOLEAN,
    ADD COLUMN failure_http_status INTEGER,
    ADD COLUMN failure_fetch_mode VARCHAR(20),
    ADD COLUMN failure_fallback_attempted BOOLEAN,
    ADD COLUMN failure_incident_id UUID;

ALTER TABLE compliance_scans
    ADD CONSTRAINT ck_compliance_scans_failure_http_status
        CHECK (failure_http_status IS NULL OR failure_http_status BETWEEN 100 AND 599),
    ADD CONSTRAINT ck_compliance_scans_failure_shape
        CHECK (
            (failure_code IS NULL
                AND failure_stage IS NULL
                AND failure_retryable IS NULL
                AND failure_http_status IS NULL
                AND failure_fetch_mode IS NULL
                AND failure_fallback_attempted IS NULL
                AND failure_incident_id IS NULL)
            OR
            (failure_code IS NOT NULL
                AND failure_stage IS NOT NULL
                AND failure_retryable IS NOT NULL
                AND failure_fallback_attempted IS NOT NULL)
        );

CREATE INDEX idx_compliance_scans_failure_code_finished
    ON compliance_scans(failure_code, finished_at)
    WHERE failure_code IS NOT NULL;

ALTER TABLE monitor_runs
    ADD COLUMN failure_code VARCHAR(50),
    ADD COLUMN failure_stage VARCHAR(30),
    ADD COLUMN failure_retryable BOOLEAN,
    ADD COLUMN failure_http_status INTEGER,
    ADD COLUMN failure_fetch_mode VARCHAR(20),
    ADD COLUMN failure_fallback_attempted BOOLEAN,
    ADD COLUMN failure_incident_id UUID;

ALTER TABLE monitor_runs
    ADD CONSTRAINT ck_monitor_runs_failure_http_status
        CHECK (failure_http_status IS NULL OR failure_http_status BETWEEN 100 AND 599),
    ADD CONSTRAINT ck_monitor_runs_failure_shape
        CHECK (
            (failure_code IS NULL
                AND failure_stage IS NULL
                AND failure_retryable IS NULL
                AND failure_http_status IS NULL
                AND failure_fetch_mode IS NULL
                AND failure_fallback_attempted IS NULL
                AND failure_incident_id IS NULL)
            OR
            (failure_code IS NOT NULL
                AND failure_stage IS NOT NULL
                AND failure_retryable IS NOT NULL
                AND failure_fallback_attempted IS NOT NULL)
        );
