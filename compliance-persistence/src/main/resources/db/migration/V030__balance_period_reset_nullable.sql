-- Синхронизация period_reset_at с planRenewsAt (docs/PLAN-payments.md, Этап 2).
--
-- В non-recurring модели «конец периода» держит app_users.plan_renews_at; scan_balances.period_reset_at
-- — его зеркало для UI. После инверсии scheduler FREE-юзер не получает повторный grantMonthly, поэтому
-- его period_reset_at = now+30d «истекает молча» и врёт фронту про несуществующий сброс. Делаем поле
-- nullable и обнуляем для FREE — одна дата-правда (null у FREE, now+30d у активного PRO/BUSINESS).

ALTER TABLE scan_balances ALTER COLUMN period_reset_at DROP NOT NULL;

-- Backfill: FREE не имеет периода сброса (квота 0, сбрасывать нечего) — синхронно с V029
-- (app_users.plan_renews_at = NULL для FREE).
UPDATE scan_balances b
SET period_reset_at = NULL
FROM app_users u
WHERE b.user_id = u.id AND u.plan = 'FREE';
