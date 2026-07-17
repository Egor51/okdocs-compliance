-- Compatibility migration: production V038 already creates this index, while
-- databases initialized from the short-lived V038 variant may not have it.

CREATE UNIQUE INDEX IF NOT EXISTS uq_monitor_runs_one_running
    ON monitor_runs(monitor_id) WHERE status = 'RUNNING';
