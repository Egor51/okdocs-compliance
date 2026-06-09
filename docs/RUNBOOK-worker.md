# Runbook — compliance-worker

Операционный контракт для compliance-worker (краулинг + анализ сканов, transactional outbox relay,
reaper зависших сканов). Применимо и к combined-процессу `compliance-app`.

## Архитектура в двух словах

- **ScanRequestedListener** (Kafka consumer) → пайплайн `CRAWLING → ANALYZING → COMPLETED|PARTIAL|FAILED`.
- **ScanLifecycleService** — строгие транзакционные переходы статуса + запись `OutboxEvent` в той же tx.
- **OutboxPublisher** — раз в 5с публикует `PENDING`-события в Kafka (`FOR UPDATE SKIP LOCKED`).
- **ScanReaper** — раз в 60с добивает сканы, зависшие дольше `compliance.scan.stale-after`.

Все три безопасны при нескольких репликах: outbox через SKIP LOCKED, consumer через Kafka consumer-group,
переходы статуса идемпотентны (гард по terminal-статусу + `@Version`).

---

## DEAD outbox events

**Симптом:** метрика `compliance_outbox_dead > 0`. Событие исчерпало `compliance.outbox.max-retries`
(worker default 10) и НЕ опубликовано в Kafka — downstream (api refund, аналитика) его не получил.

**Диагностика:**
```sql
SELECT id, event_type, topic, aggregate_id, retry_count, last_error, created_at
FROM outbox_events WHERE status = 'DEAD' ORDER BY created_at DESC;
```
`aggregate_id` = `scanId`. `last_error` — причина (обычно недоступность Kafka/брокера).

**Восстановление** (после устранения первопричины — брокер поднят, топик существует):
```sql
UPDATE outbox_events
SET status = 'PENDING', retry_count = 0, next_attempt_at = (now() AT TIME ZONE 'UTC'),
    locked_at = NULL, locked_by = NULL, last_error = NULL
WHERE status = 'DEAD' AND id = '<uuid>';   -- точечно; убрать AND id для массового реплея
```
Relay подхватит на следующем тике. **Важно:** consumer'ы идемпотентны (at-least-once), повторная
публикация безопасна.

> TZ-замечание: колонки `*_at` — naive UTC. Всегда `now() AT TIME ZONE 'UTC'`, не голый `now()`.

---

## Зависшие (stuck) сканы

**Симптом:** скан надолго застрял в `CRAWLING`/`ANALYZING`; растёт `compliance_reaper_failed`.

**Автоматика:** reaper сам переводит такие в `FAILED` через `failStuck` (порог
`compliance.scan.stale-after`, default 5m, строго > `crawler.crawler-timeout-seconds`). Для USER-сканов
api делает refund по `ScanFailedEvent`.

**Ручной разбор** (если автоматика не сработала — reaper выключен/упал):
```sql
SELECT id, status, kind, updated_at, error_message
FROM compliance_scans
WHERE status IN ('CRAWLING','ANALYZING') AND updated_at < now() - interval '10 minutes';
```
Перезапуск: вернуть в `QUEUED` (listener переподхватит при следующей доставке) либо дождаться reaper'а.
Не редактировать в терминальный статус руками без refund-логики.

---

## Premium dynamic-сканы (CABINET_PREMIUM)

- `kind = CABINET_PREMIUM` + `dynamic_required = true` → нужен доступный CDP (headless Chromium).
- **CDP недоступен → скан FAILED + refund** (не отдаём degraded static за деньги). Это by design.
- **Fail-fast на старте (две ступени):** деплой обслуживает платный поток при
  `compliance.crawler.dynamic.premium-enabled=true` (это **дефолт** в repo-yml).
  1. *Конфиг.* Если CDP не сконфигурирован (`enabled=false`, пустой `base-url`, либо `base-url` с
     ws://-схемой), **контекст НЕ стартует** — валидация `ComplianceWorkerProperties.Dynamic`
     (`isPremiumRequiresCdp` / `isBaseUrlHttpScheme`) роняет старт с понятным сообщением.
  2. *Runtime.* Если CDP сконфигурирован, но эндпоинт **мёртв** (`/json/version` не отвечает 200,
     неверный auth, сетевая изоляция), `CdpAvailabilityChecker` на `ApplicationReadyEvent` роняет
     приложение `IllegalStateException`. Так зелёный деплой не маскирует мёртвый платный поток.
  Намеренно: иначе free-сканы шли бы, скрывая мёртвый premium до первого реального платежа.
- **Health:** `/actuator/health` содержит компонент `cdp` — `UP` если premium выключен или CDP жив,
  `DOWN` если premium включён, а CDP недоступен. Monitoring видит деградацию до роста FAILED+refund.
- **`base-url` — HTTP CDP-эндпоинт, не WebSocket.** Краулер ходит на `/json/version`, `/json/list` по
  HTTP и сам выводит ws-адрес таргета (`resolveWsUrl`). `ws://`/`wss://` отвергаются валидацией.
- **Прод:** `CDP_ENABLED=true`, `CDP_BASE_URL=http://browserless:3000`, `CDP_PREMIUM_ENABLED=true`.
- **Локаль/стейдж без платного потока:** `CDP_PREMIUM_ENABLED=false` (профиль `local` / test-yml уже
  ставят его) — старт проходит, CDP не нужен; любой пришедший premium-скан осознанно FAILED+refund.
- FREE_MARKETING-сканы dynamic не используют никогда.

---

## Критичные алерты

| Метрика | Условие | Значение |
|---------|---------|----------|
| `compliance_outbox_dead` | `> 0` | События потеряны для downstream — **critical** |
| `compliance_outbox_pending` | устойчивый рост | Затык relay/Kafka — publish не успевает |
| `compliance_scan_listener_failures_total` | rate растёт | Пайплайн систематически падает |
| `compliance_reaper_failed_total` | всплеск | Массовое зависание (CDP/БД/краулер) |
| `compliance_dynamic_failure_total` | rate ≫ success | CDP-эндпоинт деградировал |
| `compliance_rule_errors_total` | рост | Регресс в правилах/контракте PageAnalysisResult |

Дашборд: `GET /actuator/prometheus`. Логи трассируются по `scanId` (MDC) — грепать `scanId=<uuid>`.

---

## Масштабирование реплик

- **Безопасно горизонтально.** Outbox — `FOR UPDATE SKIP LOCKED` (нет двойной публикации); consumer —
  Kafka consumer-group `compliance-worker` (партиции делятся между репликами).
- Лимиты per-реплика (валидируются на старте, `@ConfigurationProperties` + `@Validated`):
  - `spring.kafka.listener.concurrency` — потоков consumer'а (default 3);
  - `spring.datasource.hikari.maximum-pool-size` — пул БД (worker default 5);
  - `compliance.crawler.dynamic.concurrency` — вкладок CDP на batch;
  - `compliance.crawler.max-pages` / `page-timeout-ms` / `crawler-timeout-seconds` — лимиты краула
    (`SiteCrawler` последовательный per-scan, отдельного per-domain-concurrency нет);
  - `compliance.scan.total-deadline` — общий дедлайн скана (превышение → PARTIAL);
  - `compliance.crawler.max-body-bytes` — потолок размера HTML (анти-OOM, default 5 МБ);
  - `compliance.outbox.batch-size` / `poll-interval-ms` / backoff — пропускная способность relay.
- При росте outbox-нагрузки: см. TODO(scale) в `OutboxPublisher` — claim в короткой tx + publish вне tx.
- **Не масштабировать reaper отдельно** — он идемпотентен (`failStuck` no-op на terminal), несколько
  реплик безопасны, но смысла в этом нет.

---

## Безопасность (SSRF / краулер)

- Worker — отдельный trust boundary. `UrlValidator` режет private/loopback/link-local (вкл. cloud
  metadata `169.254.169.254`), CGNAT, reserved, IPv6 ULA/6to4/IPv4-mapped — резолвит DNS перед каждым
  fetch и redirect-хопом. CDP-фильтр валидирует IP внутри Chromium (DNS-rebinding).
- `compliance.crawler.blocked-domains` — denylist; `compliance.crawler.allowed-domains` — allowlist
  (непустой → только эти домены и поддомены).
- `compliance.crawler.respect-robots` — уважать robots.txt (default true).

## Retention / billing

- **FREE_MARKETING** сканы (лид-магнит) чистятся `GuestScanCleanupScheduler` по `scan_kind`
  старше `compliance.scan.free-marketing-retention-days` (default 7), **независимо от userId**
  (free-скан может быть от залогиненного юзера). CABINET_PREMIUM хранится в истории кабинета.
- **Refund** (api, по `ScanFailedEvent`) делается только если по `scanId` есть `DEBIT` в леджере —
  FREE_MARKETING не списывал баланс, возврата за него нет. Premium dynamic-fail → FAILED → refund
  (DEBIT есть). Идемпотентность — партиальный unique-индекс `uq_balance_txns_refund_per_scan`.
