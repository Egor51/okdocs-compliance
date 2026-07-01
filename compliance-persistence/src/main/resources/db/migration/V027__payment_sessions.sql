-- Balance-first payments (docs/PLAN-payments.md). Платёж покупает кредиты баланса; webhook лишь
-- помечает SUCCEEDED и пополняет purchasedRemaining. Premium-скан запускается отдельно через
-- /api/cabinet/scans. Заменяет checkout-flow (checkout_sessions, V019), который стартовал скан из webhook.
--
-- Колонки сразу provider-ready (Telegram/TON/Stripe добавятся без миграции схемы): provider_*,
-- idempotence_key, metadata_json, provider_payload_json. В этой итерации реализован только YooKassa.

CREATE TABLE payment_sessions (
    id                    UUID         PRIMARY KEY,
    -- публичный идентификатор для фронта/возврата (id скрываем от клиента).
    public_id             UUID         NOT NULL,
    user_id               BIGINT       NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    provider              VARCHAR(30)  NOT NULL,
    -- id платежа у провайдера; заполняется после создания у провайдера (до этого NULL).
    provider_payment_id   VARCHAR(128),
    -- id инвойса (Telegram/TON), отдельно от payment_id; задел на будущие адаптеры.
    provider_invoice_id   VARCHAR(128),
    status                VARCHAR(30)  NOT NULL,
    -- продукт из pricing-каталога (PricingPlanCode): в этой итерации только ONE_REPORT.
    product_code          VARCHAR(30)  NOT NULL,
    locale                VARCHAR(16)  NOT NULL,
    -- рынок (RU/INTL); задел на provider-routing, в этой итерации опционален.
    market                VARCHAR(16),
    -- сколько кредитов зачислится на баланс при успехе (= included_reports продукта).
    credits               INT          NOT NULL,
    amount                NUMERIC(12,2) NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    confirmation_url      TEXT,
    -- наш Idempotence-Key для исходящего create у провайдера (YooKassa требует на /payments).
    idempotence_key       VARCHAR(128),
    -- произвольная metadata, которую мы кладём в платёж провайдера (productType/userId/paymentPublicId).
    metadata_json         TEXT,
    -- сырой ответ/payload провайдера для аудита и provider-specific полей без новых колонок.
    provider_payload_json TEXT,
    expires_at            TIMESTAMP,
    paid_at               TIMESTAMP,
    canceled_at           TIMESTAMP,
    failure_reason        TEXT,
    created_at            TIMESTAMP    NOT NULL,
    updated_at            TIMESTAMP    NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_payment_sessions_status CHECK (status IN ('CREATED','CREATE_FAILED','PENDING','SUCCEEDED','CANCELED','FAILED')),
    -- CHECK выровнен с roadmap-набором PaymentProvider. Реализован только YOOKASSA; остальные
    -- зарезервированы под адаптеры, чтобы добавление провайдера не требовало миграции CHECK.
    CONSTRAINT ck_payment_sessions_provider CHECK (provider IN ('YOOKASSA','STRIPE','TELEGRAM','TON')),
    CONSTRAINT ck_payment_sessions_credits CHECK (credits > 0),
    CONSTRAINT ck_payment_sessions_amount CHECK (amount >= 0),
    CONSTRAINT ck_payment_sessions_currency CHECK (currency = UPPER(currency))
);

-- public_id уникален всегда (это внешний ключ для фронта).
CREATE UNIQUE INDEX uq_payment_sessions_public_id ON payment_sessions (public_id);

-- Идемпотентность webhook на уровне БД: один платёж провайдера = одна сессия. provider в ключе —
-- id-пространства провайдеров не пересекаются глобально.
CREATE UNIQUE INDEX uq_payment_sessions_provider_payment
    ON payment_sessions (provider, provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;

-- Idempotence-Key уникален в рамках провайдера, а не глобально: у Telegram/TON своего нашего ключа
-- может не быть, и пустые/совпадающие значения разных провайдеров не должны конфликтовать.
CREATE UNIQUE INDEX uq_payment_sessions_provider_idem
    ON payment_sessions (provider, idempotence_key)
    WHERE idempotence_key IS NOT NULL;

CREATE INDEX idx_payment_sessions_user   ON payment_sessions (user_id, created_at DESC);
CREATE INDEX idx_payment_sessions_status ON payment_sessions (status, created_at);
