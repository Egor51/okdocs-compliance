-- Paid plans PRO/BUSINESS как разовая покупка месяца (docs/PLAN-payments.md, Этап 2).
--
-- 1) Идемпотентность активации тарифа из оплаты: PLAN_GRANT привязывается к payment_sessions.id,
--    партиальный уникальный индекс делает второй PLAN_GRANT по тому же платежу невозможным на уровне
--    БД (симметрично uq_balance_txns_purchase_per_payment, V028). payment_id-колонка уже есть (V028).
CREATE UNIQUE INDEX uq_balance_txns_plan_per_payment
    ON scan_balance_txns (payment_id)
    WHERE type = 'PLAN_GRANT' AND payment_id IS NOT NULL;

-- 2) Инверсия семантики plan_renews_at: в non-recurring модели это «конец оплаченного периода», а не
--    «дата автопродления». Для FREE срок не имеет смысла (FREE не истекает и не продлевается платно) —
--    раньше его ставили при регистрации, из-за чего scheduler бесконечно «продлевал» FREE. Обнуляем,
--    чтобы expire-job (plan IN PRO/BUSINESS AND plan_renews_at <= now) не цеплял FREE-юзеров.
UPDATE app_users SET plan_renews_at = NULL WHERE plan = 'FREE';
