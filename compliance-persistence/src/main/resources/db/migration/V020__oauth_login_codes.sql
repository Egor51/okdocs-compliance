-- F.2 §F8 — one-time коды автологина после OAuth-callback'а. Success-handler редиректит на фронт
-- с кодом; фронт/BFF меняет код на JWT через /api/auth/oauth/exchange. Код одноразовый и
-- короткоживущий: токен не светится в URL/истории браузера, перехват кода даёт лишь один обмен.
CREATE TABLE oauth_login_codes (
    code_hash   VARCHAR(64) PRIMARY KEY,        -- SHA-256 кода (plain не храним, как refresh-токены)
    user_id     BIGINT      NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    expires_at  TIMESTAMP   NOT NULL,
    consumed_at TIMESTAMP,                       -- одноразовость: непустое = уже обменян
    created_at  TIMESTAMP   NOT NULL
);

CREATE INDEX idx_oauth_login_codes_expires ON oauth_login_codes (expires_at);
