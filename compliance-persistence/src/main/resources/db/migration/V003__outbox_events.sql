-- Этап 2 §2.4 — transactional outbox

CREATE TABLE outbox_events (
    id              UUID         PRIMARY KEY,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    topic           VARCHAR(150) NOT NULL,
    event_key       VARCHAR(100),
    payload         TEXT         NOT NULL,
    schema_version  INT          NOT NULL DEFAULT 1,
    status          VARCHAR(20)  NOT NULL,
    retry_count     INT          NOT NULL DEFAULT 0,
    -- NOT NULL DEFAULT now(): выборка фильтрует next_attempt_at <= now();
    -- NULL <= now() = UNKNOWN, событие зависло бы навсегда (§4.5).
    next_attempt_at TIMESTAMP    NOT NULL DEFAULT now(),
    last_error      TEXT,
    locked_at       TIMESTAMP,
    locked_by       VARCHAR(100),
    created_at      TIMESTAMP    NOT NULL,
    published_at    TIMESTAMP
);

-- Покрывает выборку relay: WHERE status=? AND next_attempt_at<=? ORDER BY created_at
CREATE INDEX idx_outbox_status_next_attempt ON outbox_events(status, next_attempt_at, created_at);
CREATE INDEX idx_outbox_aggregate           ON outbox_events(aggregate_id);
