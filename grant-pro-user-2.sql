-- Ручная активация тарифа PRO для app_users.id = 2.
-- PostgreSQL 16. Повторный запуск не выдаёт квоту заново, если PRO ещё активен.
--
-- Повторяет ключевые инварианты AdminService#setPlan:
--   * план действует 30 дней;
--   * месячная квота PRO = 30 отчётов;
--   * использованная месячная квота обнуляется;
--   * отдельно купленные отчёты (purchased_remaining) сохраняются;
--   * изменение отражается в append-only ledger scan_balance_txns.

BEGIN;

DO $$
DECLARE
    v_user_id       CONSTANT BIGINT  := 2;
    v_monthly_quota CONSTANT INTEGER := 30;
    v_now                    TIMESTAMP := CURRENT_TIMESTAMP AT TIME ZONE 'UTC';
    v_period_end             TIMESTAMP := v_now + INTERVAL '30 days';
    v_old_plan               VARCHAR(30);
    v_old_plan_renews_at     TIMESTAMP;
    v_balance_after          INTEGER;
BEGIN
    SELECT plan, plan_renews_at
      INTO v_old_plan, v_old_plan_renews_at
      FROM app_users
     WHERE id = v_user_id
       FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Пользователь с id = % не найден', v_user_id;
    END IF;

    IF v_old_plan = 'PRO' AND v_old_plan_renews_at > v_now THEN
        RAISE NOTICE 'У пользователя id = % уже активен PRO до %; изменений нет',
            v_user_id, v_old_plan_renews_at;
    ELSE
        UPDATE app_users
           SET plan = 'PRO',
               plan_renews_at = v_period_end,
               updated_at = v_now
         WHERE id = v_user_id;

        INSERT INTO scan_balances (
            user_id,
            monthly_quota,
            used_this_period,
            purchased_remaining,
            period_reset_at,
            created_at,
            updated_at,
            version
        )
        VALUES (
            v_user_id,
            v_monthly_quota,
            0,
            0,
            v_period_end,
            v_now,
            v_now,
            0
        )
        ON CONFLICT (user_id) DO UPDATE
           SET monthly_quota = EXCLUDED.monthly_quota,
               used_this_period = 0,
               period_reset_at = EXCLUDED.period_reset_at,
               updated_at = EXCLUDED.updated_at,
               version = scan_balances.version + 1
        RETURNING monthly_quota - used_this_period + purchased_remaining
             INTO v_balance_after;

        INSERT INTO scan_balance_txns (
            id,
            user_id,
            type,
            source,
            amount,
            balance_after,
            scan_id,
            payment_id,
            note,
            created_at
        )
        VALUES (
            gen_random_uuid(),
            v_user_id,
            'PLAN_GRANT',
            NULL,
            v_monthly_quota,
            v_balance_after,
            NULL,
            NULL,
            'Ручная активация PRO через SQL',
            v_now
        );

        RAISE NOTICE 'Пользователю id = % активирован PRO до %, доступно отчётов: %',
            v_user_id, v_period_end, v_balance_after;
    END IF;
END
$$;

COMMIT;

-- Контрольный результат после выполнения.
SELECT
    u.id,
    u.email,
    u.plan,
    u.plan_renews_at,
    b.monthly_quota,
    b.used_this_period,
    b.purchased_remaining,
    (b.monthly_quota - b.used_this_period + b.purchased_remaining) AS available_reports,
    b.period_reset_at
FROM app_users u
LEFT JOIN scan_balances b ON b.user_id = u.id
WHERE u.id = 2;
