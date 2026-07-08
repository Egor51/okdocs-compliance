-- DEV-ONLY сид. Применяется ТОЛЬКО combined-app'ом (compliance-app), который добавляет
-- classpath:db/migration-dev в spring.flyway.locations. Standalone api/worker (прод) эту локацию
-- НЕ сканируют — хардкод-аккаунт user@local/pass с кредитами в прод не попадает.
--
-- Логин: email = 'user@local', пароль = 'pass' (BCrypt-хеш ниже проверен BCryptPasswordEncoder.matches).
-- Баланс: monthly_quota = 10 → available = 10 (used_this_period=0, purchased_remaining=0, §2.7).
-- Идемпотентно (ON CONFLICT DO NOTHING): повторный/чистый прогон не падает.
--
-- Префикс V900 — намеренно вне общей VXXX-последовательности (db/migration): это отдельная
-- Flyway-локация со своей историей, конфликта версий с основными миграциями нет.

-- 1) Пользователь
INSERT INTO app_users (email, password_hash, name, role, status, plan, created_at, updated_at)
VALUES (
    'user@local',
    '$2a$10$M1SU4.Q5J8w90uoffMYHMe0daPHQttIknZ9y9uFya4CXhRXxOEwhi', -- bcrypt('pass')
    'Dev User',
    'USER',
    'ACTIVE',
    'FREE',
    now() AT TIME ZONE 'UTC',
    now() AT TIME ZONE 'UTC'
)
-- V021 заменил column-level UNIQUE(email) на регистронезависимый partial index
-- uq_app_users_email_ci (lower(email)) WHERE email IS NOT NULL — конфликт матчим на него же.
ON CONFLICT (lower(email)) WHERE email IS NOT NULL DO NOTHING;

-- 2) Баланс: 10 сканов в месячной квоте. period_reset_at — через месяц от старта.
INSERT INTO scan_balances (
    user_id, monthly_quota, used_this_period, purchased_remaining,
    period_reset_at, created_at, updated_at, version
)
SELECT u.id, 10, 0, 0,
       (now() AT TIME ZONE 'UTC') + interval '1 month',
       now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC', 0
FROM app_users u
WHERE u.email = 'user@local'
ON CONFLICT (user_id) DO NOTHING;

-- 3) Леджер: PLAN_GRANT на 10 (как делает ScanBalanceService.createForNewUser — баланс
--    восстановим суммой транзакций). Гард: не дублировать грант при повторном прогоне.
INSERT INTO scan_balance_txns (id, user_id, type, amount, balance_after, scan_id, note, created_at)
SELECT gen_random_uuid(), u.id, 'PLAN_GRANT', 10, 10, NULL, 'dev seed',
       now() AT TIME ZONE 'UTC'
FROM app_users u
WHERE u.email = 'user@local'
  AND NOT EXISTS (
      SELECT 1 FROM scan_balance_txns t
      WHERE t.user_id = u.id AND t.type = 'PLAN_GRANT'
  );
