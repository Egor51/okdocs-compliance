-- Идемпотентность пополнения баланса по платежу (docs/PLAN-payments.md, Фаза 3).
-- ScanBalanceService.purchase(...) не идемпотентен: повторный webhook начислил бы кредиты дважды.
-- Привязываем PURCHASE-движение к payment_sessions.id и партиальным уникальным индексом делаем
-- второй PURCHASE по тому же платежу невозможным на уровне БД (как uq_balance_txns_refund_per_scan, V011).

ALTER TABLE scan_balance_txns ADD COLUMN payment_id UUID REFERENCES payment_sessions(id);

CREATE UNIQUE INDEX uq_balance_txns_purchase_per_payment
    ON scan_balance_txns (payment_id)
    WHERE type = 'PURCHASE' AND payment_id IS NOT NULL;
