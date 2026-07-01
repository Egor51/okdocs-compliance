-- Цены зависят от локали/рынка: RU получает RUB, EN получает USD.
-- V025 уже могла быть применена с price_amount/currency на уровне pricing_plans, поэтому переносим
-- данные вперёд новой миграцией, не меняя checksum применённой V025.

ALTER TABLE pricing_plan_translations ADD COLUMN price_amount NUMERIC(12, 2);
ALTER TABLE pricing_plan_translations ADD COLUMN currency VARCHAR(3);

UPDATE pricing_plan_translations t
SET price_amount = p.price_amount,
    currency = p.currency
FROM pricing_plans p
WHERE t.plan_id = p.id;

UPDATE pricing_plan_translations t
SET price_amount = CASE p.code
        WHEN 'ONE_REPORT' THEN 990.00
        WHEN 'PRO' THEN 4990.00
        WHEN 'BUSINESS' THEN 19900.00
        ELSE t.price_amount
    END,
    currency = 'RUB'
FROM pricing_plans p
WHERE t.plan_id = p.id AND t.locale = 'ru';

UPDATE pricing_plan_translations t
SET price_amount = CASE p.code
        WHEN 'ONE_REPORT' THEN 19.00
        WHEN 'PRO' THEN 79.00
        WHEN 'BUSINESS' THEN 299.00
        ELSE t.price_amount
    END,
    currency = 'USD'
FROM pricing_plans p
WHERE t.plan_id = p.id AND t.locale = 'en';

ALTER TABLE pricing_plan_translations ALTER COLUMN price_amount SET NOT NULL;
ALTER TABLE pricing_plan_translations ALTER COLUMN currency SET NOT NULL;

ALTER TABLE pricing_plan_translations ADD CONSTRAINT ck_pricing_plan_translation_price
    CHECK (price_amount >= 0);
ALTER TABLE pricing_plan_translations ADD CONSTRAINT ck_pricing_plan_translation_currency
    CHECK (currency = UPPER(currency));

ALTER TABLE pricing_plans DROP CONSTRAINT IF EXISTS ck_pricing_plan_price;
ALTER TABLE pricing_plans DROP CONSTRAINT IF EXISTS ck_pricing_plan_currency;
ALTER TABLE pricing_plans DROP COLUMN price_amount;
ALTER TABLE pricing_plans DROP COLUMN currency;
