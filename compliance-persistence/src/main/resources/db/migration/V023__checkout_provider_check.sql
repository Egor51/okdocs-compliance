-- P3 — checkout_sessions.provider ограничен фиксированным набором PaymentProvider (§4a/F.4):
-- БД сама валидирует значение, не полагаясь только на JPA enum. NULL допустим (provider
-- проставляется лишь при обработке webhook'а, до этого сессия в статусе CREATED).
ALTER TABLE checkout_sessions ADD CONSTRAINT ck_checkout_provider CHECK (
    provider IS NULL OR provider IN ('YOOKASSA', 'STRIPE', 'CRYPTO')
);
