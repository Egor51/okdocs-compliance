-- Recurring premium scans for sites monitored every two or three days.

CREATE TABLE site_monitors (
    id                    UUID        PRIMARY KEY,
    user_id               BIGINT      NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    site_url              TEXT        NOT NULL,
    site_domain           VARCHAR(255) NOT NULL,
    scan_jurisdiction     VARCHAR(30) NOT NULL,
    report_locale         VARCHAR(16) NOT NULL,
    status                VARCHAR(40) NOT NULL,
    interval_days         INT         NOT NULL,
    timezone              VARCHAR(64) NOT NULL,
    notifications_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    last_scan_id          UUID        REFERENCES compliance_scans(id) ON DELETE SET NULL,
    last_score            INT,
    last_run_at           TIMESTAMP,
    next_run_at           TIMESTAMP   NOT NULL,
    locked_at             TIMESTAMP,
    locked_by             VARCHAR(120),
    lock_token            UUID,
    created_at            TIMESTAMP   NOT NULL,
    updated_at            TIMESTAMP   NOT NULL,
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_site_monitor_status CHECK (
        status IN ('ACTIVE', 'PAUSED', 'PAUSED_NO_BALANCE', 'PAUSED_PLAN_EXPIRED')),
    CONSTRAINT ck_site_monitor_interval CHECK (interval_days IN (2, 3)),
    CONSTRAINT ck_site_monitor_jurisdiction CHECK (
        scan_jurisdiction IN ('RU', 'EU', 'UK', 'DE', 'FR', 'ES'))
);

CREATE UNIQUE INDEX uq_site_monitors_user_domain_jurisdiction
    ON site_monitors(user_id, lower(site_domain), scan_jurisdiction);
CREATE INDEX idx_site_monitors_due
    ON site_monitors(status, next_run_at) WHERE status = 'ACTIVE';

ALTER TABLE compliance_scans
    ADD COLUMN monitor_id UUID REFERENCES site_monitors(id) ON DELETE SET NULL,
    ADD COLUMN launch_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE compliance_scans ADD CONSTRAINT ck_scan_launch_source
    CHECK (launch_source IN ('MANUAL', 'MONITORING'));
CREATE INDEX idx_compliance_scans_monitor_created
    ON compliance_scans(monitor_id, created_at DESC) WHERE monitor_id IS NOT NULL;

CREATE TABLE monitor_runs (
    id                UUID        PRIMARY KEY,
    monitor_id        UUID        NOT NULL REFERENCES site_monitors(id) ON DELETE CASCADE,
    scan_id           UUID        REFERENCES compliance_scans(id) ON DELETE SET NULL,
    trigger           VARCHAR(20) NOT NULL,
    status            VARCHAR(20) NOT NULL,
    scheduled_for     TIMESTAMP   NOT NULL,
    previous_score    INT,
    current_score     INT,
    new_findings      INT,
    resolved_findings INT,
    error_message     TEXT,
    created_at        TIMESTAMP   NOT NULL,
    finished_at       TIMESTAMP,
    CONSTRAINT ck_monitor_run_trigger CHECK (trigger IN ('SCHEDULE', 'MANUAL')),
    CONSTRAINT ck_monitor_run_status CHECK (
        status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'SKIPPED'))
);

CREATE UNIQUE INDEX uq_monitor_runs_monitor_scheduled
    ON monitor_runs(monitor_id, scheduled_for, trigger);
CREATE UNIQUE INDEX uq_monitor_runs_scan
    ON monitor_runs(scan_id) WHERE scan_id IS NOT NULL;
CREATE UNIQUE INDEX uq_monitor_runs_one_running
    ON monitor_runs(monitor_id) WHERE status = 'RUNNING';
CREATE INDEX idx_monitor_runs_monitor_created
    ON monitor_runs(monitor_id, created_at DESC);
