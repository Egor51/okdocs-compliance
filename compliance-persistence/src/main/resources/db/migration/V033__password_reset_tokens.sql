CREATE TABLE password_reset_tokens (
    id           UUID        PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    expires_at   TIMESTAMP   NOT NULL,
    used_at      TIMESTAMP,
    requested_ip VARCHAR(45),
    created_at   TIMESTAMP   NOT NULL
);

CREATE INDEX idx_password_reset_tokens_user_created
    ON password_reset_tokens(user_id, created_at DESC);
CREATE INDEX idx_password_reset_tokens_expiry
    ON password_reset_tokens(expires_at);
