CREATE TABLE mail_outbox (
    id               UUID         PRIMARY KEY,
    idempotency_key  VARCHAR(255) NOT NULL UNIQUE,
    mail_type        VARCHAR(40)  NOT NULL,
    aggregate_id     VARCHAR(100),
    recipient        VARCHAR(255) NOT NULL,
    subject          VARCHAR(500) NOT NULL,
    template_name    VARCHAR(100) NOT NULL,
    locale           VARCHAR(10)  NOT NULL,
    model_payload    TEXT         NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    attempt_count    INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMP    NOT NULL DEFAULT now(),
    last_error       TEXT,
    locked_at        TIMESTAMP,
    locked_by        VARCHAR(100),
    lock_token       UUID,
    created_at       TIMESTAMP    NOT NULL,
    sent_at          TIMESTAMP,
    purged_at        TIMESTAMP,
    CONSTRAINT chk_mail_outbox_status
        CHECK (status IN ('PENDING', 'SENT', 'DEAD', 'SIMULATED', 'CANCELLED'))
);

CREATE INDEX idx_mail_outbox_dispatch
    ON mail_outbox(status, next_attempt_at, created_at);
CREATE INDEX idx_mail_outbox_aggregate
    ON mail_outbox(mail_type, aggregate_id);
