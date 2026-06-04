-- Этап 2 §2.7 — баланс сканов + append-only леджер

CREATE TABLE scan_balances (
    user_id             BIGINT    PRIMARY KEY REFERENCES app_users(id),
    monthly_quota       INT       NOT NULL DEFAULT 0,
    used_this_period    INT       NOT NULL DEFAULT 0,
    purchased_remaining INT       NOT NULL DEFAULT 0,
    period_reset_at     TIMESTAMP NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    version             BIGINT    NOT NULL DEFAULT 0
);

CREATE TABLE scan_balance_txns (
    id            UUID        PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES app_users(id),
    type          VARCHAR(30) NOT NULL,
    amount        INT         NOT NULL,
    balance_after INT         NOT NULL,
    scan_id       UUID,
    note          TEXT,
    created_at    TIMESTAMP   NOT NULL
);

CREATE INDEX idx_balance_txns_user_created ON scan_balance_txns(user_id, created_at DESC);
CREATE INDEX idx_balance_txns_scan         ON scan_balance_txns(scan_id);
