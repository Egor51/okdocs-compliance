-- F.1 §F3 — из какого кармана прошло движение скана (MONTHLY | PURCHASED).
-- Обязателен для DEBIT (какой карман списан) и REFUND (копирует source исходного DEBIT);
-- для PURCHASE/PLAN_GRANT/ADMIN_ADJUST/EXPIRE — NULL.
ALTER TABLE scan_balance_txns ADD COLUMN source VARCHAR(16);

-- Backfill: до этой правки всё списание де-факто шло из месячной квоты (FREE=1, докупок не было),
-- поэтому старые DEBIT/REFUND-строки относим к MONTHLY — иначе CHECK ниже их отверг бы.
UPDATE scan_balance_txns SET source = 'MONTHLY' WHERE type IN ('DEBIT', 'REFUND') AND source IS NULL;

-- Инвариант на уровне БД (как CHECK в V005): source обязателен ровно для DEBIT/REFUND и
-- запрещён для остальных типов — БД не даст записать рассинхрон мимо ScanBalanceService.
ALTER TABLE scan_balance_txns ADD CONSTRAINT ck_balance_txns_source CHECK (
    (type IN ('DEBIT', 'REFUND') AND source IS NOT NULL)
    OR (type NOT IN ('DEBIT', 'REFUND') AND source IS NULL)
);
