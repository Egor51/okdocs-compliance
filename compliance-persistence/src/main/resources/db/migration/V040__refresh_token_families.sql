-- Безопасная ротация refresh-токенов: одна family соответствует одной сессии/устройству.
-- Старые токены становятся отдельными families, поэтому reuse больше не затрагивает другие входы.

ALTER TABLE refresh_tokens ADD COLUMN family_id UUID;
UPDATE refresh_tokens SET family_id = id;

-- Совместимость с ещё работающей старой API-репликой во время rolling deployment:
-- старый код не передаёт family_id, но всегда заранее генерирует id токена.
CREATE FUNCTION set_refresh_token_family_id() RETURNS trigger AS $$
BEGIN
    IF NEW.family_id IS NULL THEN
        NEW.family_id := NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_refresh_tokens_family_id
    BEFORE INSERT ON refresh_tokens
    FOR EACH ROW EXECUTE FUNCTION set_refresh_token_family_id();

ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;

ALTER TABLE refresh_tokens
    ADD COLUMN replaced_by_id UUID REFERENCES refresh_tokens(id),
    ADD COLUMN rotation_grace_until TIMESTAMP;

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);
CREATE UNIQUE INDEX uq_refresh_tokens_token_hash ON refresh_tokens(token_hash);
