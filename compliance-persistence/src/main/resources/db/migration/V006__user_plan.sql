-- Этап 2 §2.1 — тариф пользователя (добавление к app_users)

ALTER TABLE app_users ADD COLUMN plan VARCHAR(30) NOT NULL DEFAULT 'FREE';
ALTER TABLE app_users ADD COLUMN plan_renews_at TIMESTAMP;
