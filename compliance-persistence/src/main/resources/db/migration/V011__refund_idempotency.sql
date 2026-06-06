-- Этап 4 (правка) — атомарная идемпотентность refund.
-- Гонка at-least-once Kafka: два параллельных ScanFailedEvent оба проходят
-- existsByScanIdAndType до коммита и возвращают баланс дважды. Партиальный уникальный
-- индекс делает второй REFUND по тому же scan_id невозможным на уровне БД — вставка падает
-- с unique violation, который сервис ловит как «возврат уже выполнен».
CREATE UNIQUE INDEX uq_balance_txns_refund_per_scan
    ON scan_balance_txns (scan_id)
    WHERE type = 'REFUND';
