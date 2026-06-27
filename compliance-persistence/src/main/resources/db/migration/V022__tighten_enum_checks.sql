-- P3 — ужесточение CHECK для ledger/payment-данных: БД сама валидирует значения enum, не только
-- их наличие/отсутствие (JPA enum защищает на уровне приложения, но инварианты денег должны
-- держаться и при прямом доступе к БД).

-- scan_balance_txns.source: помимо required-for-DEBIT/REFUND ещё и корректное значение кармана.
ALTER TABLE scan_balance_txns DROP CONSTRAINT IF EXISTS ck_balance_txns_source;
ALTER TABLE scan_balance_txns ADD CONSTRAINT ck_balance_txns_source CHECK (
    (type IN ('DEBIT', 'REFUND') AND source IN ('MONTHLY', 'PURCHASED'))
    OR (type NOT IN ('DEBIT', 'REFUND') AND source IS NULL)
);

-- checkout_sessions.status: только значения CheckoutStatus.
ALTER TABLE checkout_sessions ADD CONSTRAINT ck_checkout_status CHECK (
    status IN ('CREATED', 'PAID_CONSUMED', 'PAID_NOT_CONSUMED', 'PAID_FAILED_TO_START')
);
