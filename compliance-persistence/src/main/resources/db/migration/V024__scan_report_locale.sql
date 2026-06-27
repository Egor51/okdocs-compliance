-- Локализация отчёта (PLAN-evidence-localization, Этап 1): язык evidence/message ≠ jurisdiction.
-- nullable — legacy-сканы/сессии без locale; worker подставляет дефолт (ru) при отсутствии.
ALTER TABLE compliance_scans ADD COLUMN report_locale VARCHAR(16);
ALTER TABLE checkout_sessions ADD COLUMN report_locale VARCHAR(16);
