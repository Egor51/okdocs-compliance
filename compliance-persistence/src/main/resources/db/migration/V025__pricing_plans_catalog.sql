-- Каталог тарифов для UI. Тексты хранятся в БД по locale, чтобы менять pricing без релиза.
-- ONE_REPORT — разовая покупка отчёта, PRO/BUSINESS — подписочные продукты.

CREATE TABLE pricing_plans (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(30)    NOT NULL UNIQUE,
    active           BOOLEAN        NOT NULL DEFAULT TRUE,
    price_amount     NUMERIC(12, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    billing_period   VARCHAR(20)    NOT NULL,
    included_reports INT            NOT NULL,
    highlighted      BOOLEAN        NOT NULL DEFAULT FALSE,
    sort_order       INT            NOT NULL,
    created_at       TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_pricing_plan_code CHECK (code IN ('ONE_REPORT', 'PRO', 'BUSINESS')),
    CONSTRAINT ck_pricing_plan_billing_period CHECK (billing_period IN ('ONE_TIME', 'MONTH')),
    CONSTRAINT ck_pricing_plan_price CHECK (price_amount >= 0),
    CONSTRAINT ck_pricing_plan_reports CHECK (included_reports >= 0),
    CONSTRAINT ck_pricing_plan_currency CHECK (currency = UPPER(currency))
);

CREATE TABLE pricing_plan_translations (
    id           BIGSERIAL PRIMARY KEY,
    plan_id      BIGINT       NOT NULL REFERENCES pricing_plans(id) ON DELETE CASCADE,
    locale       VARCHAR(16)  NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    description  TEXT         NOT NULL,
    cta_label    VARCHAR(120) NOT NULL,
    CONSTRAINT uq_pricing_plan_translation_locale UNIQUE (plan_id, locale)
);

CREATE TABLE pricing_plan_features (
    id             BIGSERIAL PRIMARY KEY,
    translation_id BIGINT NOT NULL REFERENCES pricing_plan_translations(id) ON DELETE CASCADE,
    text           TEXT   NOT NULL,
    sort_order     INT    NOT NULL,
    CONSTRAINT uq_pricing_plan_feature_order UNIQUE (translation_id, sort_order)
);

CREATE INDEX idx_pricing_plans_active_sort ON pricing_plans (active, sort_order);
CREATE INDEX idx_pricing_plan_translations_locale ON pricing_plan_translations (locale);

INSERT INTO pricing_plans (code, price_amount, currency, billing_period, included_reports, highlighted, sort_order)
VALUES
    ('ONE_REPORT', 990.00, 'RUB', 'ONE_TIME', 1, FALSE, 10),
    ('PRO', 4990.00, 'RUB', 'MONTH', 30, TRUE, 20),
    ('BUSINESS', 19900.00, 'RUB', 'MONTH', 200, FALSE, 30);

WITH one_report AS (
    SELECT id FROM pricing_plans WHERE code = 'ONE_REPORT'
), ru AS (
    INSERT INTO pricing_plan_translations (plan_id, locale, display_name, description, cta_label)
    SELECT id, 'ru', '1 отчёт',
           'Разовая покупка полного compliance-отчёта для одного сайта без подписки.',
           'Купить отчёт'
    FROM one_report
    RETURNING id
), en AS (
    INSERT INTO pricing_plan_translations (plan_id, locale, display_name, description, cta_label)
    SELECT id, 'en', '1 report',
           'One-time purchase of a full compliance report for one website without a subscription.',
           'Buy report'
    FROM one_report
    RETURNING id
)
INSERT INTO pricing_plan_features (translation_id, text, sort_order)
SELECT id, text, sort_order
FROM ru, (VALUES
    ('Полный premium-отчёт по выбранной юрисдикции', 10),
    ('Доказательства, рекомендации и оценка риска', 20),
    ('Без ежемесячной подписки', 30)
) AS f(text, sort_order)
UNION ALL
SELECT id, text, sort_order
FROM en, (VALUES
    ('Full premium report for the selected jurisdiction', 10),
    ('Evidence, recommendations and risk score', 20),
    ('No monthly subscription', 30)
) AS f(text, sort_order);

WITH pro AS (
    SELECT id FROM pricing_plans WHERE code = 'PRO'
), ru AS (
    INSERT INTO pricing_plan_translations (plan_id, locale, display_name, description, cta_label)
    SELECT id, 'ru', 'PRO',
           'Для регулярной проверки сайтов и лендингов небольшой команды.',
           'Выбрать PRO'
    FROM pro
    RETURNING id
), en AS (
    INSERT INTO pricing_plan_translations (plan_id, locale, display_name, description, cta_label)
    SELECT id, 'en', 'PRO',
           'For recurring checks of websites and landing pages by a small team.',
           'Choose PRO'
    FROM pro
    RETURNING id
)
INSERT INTO pricing_plan_features (translation_id, text, sort_order)
SELECT id, text, sort_order
FROM ru, (VALUES
    ('30 premium-отчётов в месяц', 10),
    ('RU, EU, UK и локальные EU-overlay юрисдикции', 20),
    ('История проверок и баланс в кабинете', 30)
) AS f(text, sort_order)
UNION ALL
SELECT id, text, sort_order
FROM en, (VALUES
    ('30 premium reports per month', 10),
    ('RU, EU, UK and local EU overlay jurisdictions', 20),
    ('Scan history and balance in the cabinet', 30)
) AS f(text, sort_order);

WITH business AS (
    SELECT id FROM pricing_plans WHERE code = 'BUSINESS'
), ru AS (
    INSERT INTO pricing_plan_translations (plan_id, locale, display_name, description, cta_label)
    SELECT id, 'ru', 'BUSINESS',
           'Для агентств и компаний с большим количеством сайтов и регулярным контролем compliance.',
           'Выбрать BUSINESS'
    FROM business
    RETURNING id
), en AS (
    INSERT INTO pricing_plan_translations (plan_id, locale, display_name, description, cta_label)
    SELECT id, 'en', 'BUSINESS',
           'For agencies and companies that manage many websites and need recurring compliance control.',
           'Choose BUSINESS'
    FROM business
    RETURNING id
)
INSERT INTO pricing_plan_features (translation_id, text, sort_order)
SELECT id, text, sort_order
FROM ru, (VALUES
    ('200 premium-отчётов в месяц', 10),
    ('Подходит для портфеля сайтов и клиентских проектов', 20),
    ('Приоритет для масштабных проверок', 30)
) AS f(text, sort_order)
UNION ALL
SELECT id, text, sort_order
FROM en, (VALUES
    ('200 premium reports per month', 10),
    ('Fits website portfolios and client projects', 20),
    ('Priority for larger scan volumes', 30)
) AS f(text, sort_order);
