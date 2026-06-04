-- Этап 2 §2.5 — отложенные/запланированные задачи

CREATE TABLE scheduled_tasks (
    id           UUID         PRIMARY KEY,
    user_id      BIGINT       REFERENCES app_users(id),
    guest_id     UUID,
    scan_id      UUID         REFERENCES compliance_scans(id),
    task_type    VARCHAR(50)  NOT NULL,
    status       VARCHAR(30)  NOT NULL,
    payload      TEXT         NOT NULL,
    scheduled_at TIMESTAMP    NOT NULL,
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP,
    retry_count  INT          NOT NULL DEFAULT 0,
    max_retries  INT          NOT NULL DEFAULT 3,
    last_error   TEXT,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP
);

CREATE INDEX idx_scheduled_tasks_status_scheduled ON scheduled_tasks(status, scheduled_at);
CREATE INDEX idx_scheduled_tasks_user_created     ON scheduled_tasks(user_id, created_at DESC);
CREATE INDEX idx_scheduled_tasks_guest_created    ON scheduled_tasks(guest_id, created_at DESC);
CREATE INDEX idx_scheduled_tasks_scan_id          ON scheduled_tasks(scan_id);
