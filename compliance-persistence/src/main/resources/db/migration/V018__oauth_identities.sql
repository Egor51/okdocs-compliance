-- F.2 §F6/F7 — соц-логин (OAuth). Аккаунты из OAuth-профиля пароля не имеют.

-- F6: password_hash больше не обязателен (OAuth-only аккаунт без пароля).
ALTER TABLE app_users ALTER COLUMN password_hash DROP NOT NULL;

-- F7: связки внешних OAuth-личностей с локальным аккаунтом. Отдельная таблица (НЕ поля в
-- app_users), т.к. у одного юзера может быть несколько провайдеров.
CREATE TABLE oauth_identities (
    id               BIGSERIAL    PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    provider         VARCHAR(30)  NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    email            VARCHAR(255),
    email_verified   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP    NOT NULL,
    -- одна и та же внешняя личность (provider + её id) не может быть привязана дважды.
    CONSTRAINT uq_oauth_provider_user UNIQUE (provider, provider_user_id)
);

CREATE INDEX idx_oauth_identities_user ON oauth_identities(user_id);
