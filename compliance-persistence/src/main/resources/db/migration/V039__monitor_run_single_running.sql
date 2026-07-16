-- A monitor can have only one active execution at a time.
-- Kept separate because V038 had already been applied before this invariant was introduced.

CREATE UNIQUE INDEX uq_monitor_runs_one_running
    ON monitor_runs(monitor_id) WHERE status = 'RUNNING';
