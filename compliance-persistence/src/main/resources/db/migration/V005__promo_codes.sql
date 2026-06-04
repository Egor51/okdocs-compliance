-- Этап 2 §2.3 — промокоды (опционально для MVP)

CREATE TABLE compliance_promo_codes (
    id           UUID        PRIMARY KEY,
    code         VARCHAR(64) NOT NULL UNIQUE,
    discount_pct INT         NOT NULL CHECK (discount_pct BETWEEN 5 AND 100),
    max_uses     INT,
    used_count   INT         NOT NULL DEFAULT 0,
    expires_at   TIMESTAMP,
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP   NOT NULL
);
