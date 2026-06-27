-- F.2 (правка) — OAuth-провайдер может не отдать email (скрыт/не выдан scope). Старая схема
-- email NOT NULL UNIQUE роняла бы INSERT такого аккаунта. Делаем email nullable и переводим
-- уникальность на case-insensitive partial index (NULL-ы не конфликтуют между собой).
ALTER TABLE app_users ALTER COLUMN email DROP NOT NULL;

-- Снять старый column-level UNIQUE (имя по умолчанию Postgres: <table>_<column>_key).
ALTER TABLE app_users DROP CONSTRAINT IF EXISTS app_users_email_key;

-- Регистронезависимая уникальность только для непустых email (совпадает с findByEmailIgnoreCase).
CREATE UNIQUE INDEX uq_app_users_email_ci
    ON app_users (lower(email))
    WHERE email IS NOT NULL;
