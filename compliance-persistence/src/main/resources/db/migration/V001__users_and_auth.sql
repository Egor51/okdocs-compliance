-- Этап 2 §2.1 — пользователи и refresh-токены

CREATE TABLE app_users (
    id            BIGSERIAL     PRIMARY KEY,
    email         VARCHAR(255)  NOT NULL UNIQUE,
    password_hash VARCHAR(255)  NOT NULL,
    name          VARCHAR(255),
    role          VARCHAR(30)   NOT NULL,
    status        VARCHAR(30)   NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP,
    last_login_at TIMESTAMP
);

CREATE TABLE refresh_tokens (
    id          UUID          PRIMARY KEY,
    user_id     BIGINT        NOT NULL REFERENCES app_users(id),
    token_hash  VARCHAR(255)  NOT NULL,
    expires_at  TIMESTAMP     NOT NULL,
    revoked     BOOLEAN       NOT NULL DEFAULT FALSE,
    revoked_at  TIMESTAMP,
    created_at  TIMESTAMP     NOT NULL,
    user_agent  TEXT,
    ip_address  VARCHAR(45)
);

CREATE INDEX idx_refresh_tokens_user        ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash  ON refresh_tokens(token_hash);
