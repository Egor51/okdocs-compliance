-- F.4 §F12/F14 — checkout-сессии: связка «юзер начал платить» ↔ асинхронный webhook оплаты.
-- Переживает редирект к провайдеру; обеспечивает идемпотентность (provider_payment_id UNIQUE)
-- и восстановление при сбое старта скана (статусы PAID_*).
CREATE TABLE checkout_sessions (
    id                  UUID         PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    -- недоверенный prefill, валидируется бэкендом; нормализованный siteUrl/домен сохраняем при создании.
    site_url            VARCHAR(2048) NOT NULL,
    site_domain         VARCHAR(255) NOT NULL,
    jurisdiction        VARCHAR(16)  NOT NULL,
    promo_code          VARCHAR(64),
    status              VARCHAR(30)  NOT NULL,
    -- платёжный провайдер (PaymentProvider: YOOKASSA/STRIPE/CRYPTO); CHECK добавляется в V023.
    provider            VARCHAR(30),
    -- idempotency-ключ платежа от провайдера: повторный webhook с тем же id не обрабатывается дважды.
    provider_payment_id VARCHAR(255),
    -- запущенный premium-скан (после consume) — для истории кабинета и retry.
    premium_scan_id     UUID         REFERENCES compliance_scans(id) ON DELETE SET NULL,
    amount              NUMERIC(12,2),
    currency            VARCHAR(8),
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    consumed_at         TIMESTAMP
);

-- Идемпотентность webhook на уровне БД: один платёж провайдера = одна сессия его обработавшая.
CREATE UNIQUE INDEX uq_checkout_provider_payment
    ON checkout_sessions (provider, provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;

CREATE INDEX idx_checkout_sessions_user   ON checkout_sessions (user_id, created_at DESC);
CREATE INDEX idx_checkout_sessions_status ON checkout_sessions (status);
