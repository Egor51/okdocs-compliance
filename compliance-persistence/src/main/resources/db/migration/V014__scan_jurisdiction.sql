-- Этап 5.5 — явная юрисдикция скана: «по какому закону проверяем» (152-ФЗ / GDPR), приходит с
-- фронта в ScanRequest/FreeScanRequest. Это НЕ страна хостинга (host_country отвечает на «где
-- сервер»): RU-сайт может хоститься в DE, .ru не гарантия. Worker по этому полю выбирает набор
-- правил (RuleEngine гейтит по jurisdiction), а API — тариф (цена различается по юрисдикции).
--
-- DEFAULT 'RU' для существующих строк: до этого продукт был RU-only (все правила 152-ФЗ), так что
-- бэкафилл в RU корректен. Для новых сканов API проставляет реальное значение из запроса.

ALTER TABLE compliance_scans ADD COLUMN scan_jurisdiction VARCHAR(30) NOT NULL DEFAULT 'RU';
