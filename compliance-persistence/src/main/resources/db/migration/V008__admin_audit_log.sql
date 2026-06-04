-- Этап 2 §2.8 — журнал действий админа (append-only)

CREATE TABLE admin_audit_log (
    id             UUID        PRIMARY KEY,
    admin_user_id  BIGINT      NOT NULL REFERENCES app_users(id),
    action         VARCHAR(40) NOT NULL,
    target_user_id BIGINT      REFERENCES app_users(id),
    reason         TEXT        NOT NULL,
    details_json   TEXT,
    created_at     TIMESTAMP   NOT NULL
);

CREATE INDEX idx_admin_audit_admin_created  ON admin_audit_log(admin_user_id, created_at DESC);
CREATE INDEX idx_admin_audit_target_created ON admin_audit_log(target_user_id, created_at DESC);
