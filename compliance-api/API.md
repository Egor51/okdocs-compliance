# compliance-api API

Документ описывает фактический REST API модуля `compliance-api` по текущему коду:
`io.okdocs.compliance.api.web.*` и DTO из `compliance-contracts`.

`compliance-api` принимает HTTP-запросы, управляет guest/user/admin-аутентификацией, создаёт
сканы, списывает баланс для кабинетных сканов, пишет команду `ScanRequestedEvent` в transactional
outbox и отдаёт статус/отчёт, сформированный worker'ом.

## Базовые правила

- Базовый URL локально: `http://localhost:8080`.
- Все API-методы находятся под префиксом `/api`, кроме actuator endpoints.
- Тело запросов и ответов: JSON.
- Даты/время: ISO-8601 `Instant`, например `2026-06-17T12:34:56.789Z`.
- Идентификаторы сканов и audit-log: UUID.
- Аутентификация: `Authorization: Bearer <accessToken>`.
- Access token бывает двух типов:
  - guest JWT: выдаётся `POST /api/auth/guest`, используется для free marketing flow;
  - user/admin JWT: выдаётся `POST /api/auth/register`, `POST /api/auth/login` или
    `POST /api/auth/refresh`.
- Сервис stateless: серверная HTTP-сессия не используется.
- CORS и CSRF в `SecurityConfig` отключены.

## Роли и доступы

| Зона | Доступ |
|---|---|
| `POST /api/auth/guest` | открыт |
| `POST /api/auth/register` | открыт |
| `POST /api/auth/password/forgot` | открыт |
| `POST /api/auth/password/reset` | открыт |
| `POST /api/mail/unsubscribe` | открыт |
| `POST /api/auth/login` | открыт |
| `POST /api/auth/refresh` | открыт |
| `POST /api/auth/logout` | открыт |
| `GET /api/auth/me` | открыт |
| `POST /api/free-scans` | любой валидный JWT: `GUEST`, `USER`, `ADMIN` |
| `GET /api/compliance-scans` | только `USER`/`ADMIN` |
| `GET /api/compliance-scans/{id}` | владелец скана: userId или guestId из JWT |
| `GET /api/compliance-scans/{id}/report` | владелец скана |
| `POST /api/compliance-scans/{id}/email` | владелец скана |
| `/api/cabinet/**` | только `USER`/`ADMIN` |
| `/api/admin/**` | только `ADMIN` |
| `/actuator/health/**`, `/actuator/info` | открыты |

`ADMIN` дополнительно получает `ROLE_USER`, поэтому имеет доступ к cabinet-зоне. При чтении скана
через обычные `/api/compliance-scans/{id}` методы всё равно действует owner-check. Обход owner-check
для админов есть только в специальных admin endpoints.

## Основные flow

### 1. Guest free scan

1. Клиент получает guest token:

```http
POST /api/auth/guest
```

2. Клиент запускает бесплатный маркетинговый скан:

```http
POST /api/free-scans
Authorization: Bearer <guestAccessToken>
Content-Type: application/json

{
  "siteUrl": "https://example.ru",
  "jurisdiction": "RU"
}
```

3. Клиент опрашивает статус:

```http
GET /api/compliance-scans/{scanId}
Authorization: Bearer <sameGuestAccessToken>
```

4. Когда `status` стал `COMPLETED` или `PARTIAL`, клиент читает отчёт:

```http
GET /api/compliance-scans/{scanId}/report
Authorization: Bearer <sameGuestAccessToken>
```

Важно: для guest-скана нужно переиспользовать тот же guest token. Owner-check идёт по `guestId`;
новый guest token создаст новый `guestId` и получит `403` на старый скан.

Характеристики free scan:

- `kind = FREE_MARKETING`;
- `tier = FREE`;
- `maxPages = compliance.scan.free-marketing-max-pages`, по умолчанию `1`;
- `dynamicRequired = false`;
- баланс пользователя не списывается;
- отчёт возвращается в free-варианте: premium-поля findings скрыты, в ответ добавляется `paywallCta`.

### 2. User cabinet scan

1. Пользователь регистрируется или входит:

```http
POST /api/auth/login
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "password123"
}
```

2. Клиент запускает кабинетный скан:

```http
POST /api/cabinet/scans
Authorization: Bearer <userAccessToken>
Content-Type: application/json

{
  "siteUrl": "https://example.ru",
  "jurisdiction": "RU",
  "parentScanId": null
}
```

Характеристики cabinet scan:

- доступен только зарегистрированным пользователям;
- `kind = CABINET_PREMIUM`;
- effective tier отчёта: `PREMIUM`;
- `maxPages = compliance.scan.user-max-pages`;
- `dynamicRequired = true`;
- до постановки в очередь списывается `1` кредит баланса;
- если worker завершит скан как `FAILED`, кредит возвращается по `ScanFailedEvent`;
- при отсутствии доступных кредитов API отвечает `402 Payment Required`.

### 3. Повторная проверка

Для кабинетного скана можно передать `parentScanId`.

Backend проверяет:

- родительский скан существует;
- родительский скан принадлежит тому же пользователю;
- домен нового URL совпадает с доменом родительского скана.

Если домен не совпадает, вернётся `400`.

## Ошибки

Все доменные ошибки из `GlobalExceptionHandler` возвращаются в едином формате:

```json
{
  "timestamp": "2026-06-17T12:34:56.789Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Текст ошибки",
  "path": "/api/example"
}
```

| HTTP | Причина |
|---|---|
| `200 OK` | успешное чтение |
| `201 Created` | регистрация пользователя |
| `202 Accepted` | скан принят в очередь |
| `204 No Content` | мутация выполнена, тело отсутствует |
| `400 Bad Request` | Bean Validation или доменная валидация |
| `401 Unauthorized` | нет валидного JWT для защищённого endpoint'а |
| `402 Payment Required` | недостаточно баланса сканов |
| `403 Forbidden` | доступ к чужому скану или недостаточная роль |
| `404 Not Found` | скан не найден |
| `409 Conflict` | отчёт ещё не готов |
| `429 Too Many Requests` | превышен rate limit |
| `500 Internal Server Error` | необработанная ошибка |

Bean Validation ошибки склеиваются в строку вида:

```json
{
  "message": "email: must not be blank; password: size must be between 8 and 100"
}
```

## Rate limiting

Rate limit применяется при запуске скана:

| Principal | Ключ лимита | Лимит по умолчанию |
|---|---|---|
| guest | IP-адрес клиента | `5` сканов в час |
| user/admin | `userId` | `20` сканов в час |

Конфигурация:

```yaml
compliance:
  rate-limit:
    guest-scans-per-ip-per-hour: 5
    user-scans-per-hour: 20
```

В профиле `local` используется in-memory Bucket4j. В остальных профилях используется Redis
(`RedisRateLimitService`), поэтому лимит общий для всех API-инстансов.

IP берётся из `request.getRemoteAddr()`. Заголовок `X-Forwarded-For` учитывается только если явно
включено:

```yaml
compliance:
  security:
    trust-forwarded-header: true
```

Включать это нужно только за доверенным ingress/proxy, который перезаписывает `X-Forwarded-For`.

## URL и юрисдикция скана

`siteUrl` валидируется `UrlValidatorService`:

- если схема не указана, backend добавляет `https://`;
- разрешены только `http` и `https`;
- host должен извлекаться из URI;
- домен приводится к ASCII через IDN и lower-case;
- домен должен резолвиться;
- IP-адреса private/loopback/link-local/multicast отклоняются как SSRF-риск.

`jurisdiction` парсится строго в `ScanJurisdiction`:

| Значение | Назначение |
|---|---|
| `RU` | Россия / 152-ФЗ rules |
| `EU` | зарезервировано контрактом |
| `GM` | зарезервировано контрактом |

Значение обрезается по краям и приводится к upper-case. Пустая или неизвестная юрисдикция даёт
`400`.

## Auth API

### POST /api/auth/guest

Выдаёт guest access token. Тело запроса отсутствует.

Доступ: открыт.

Ответ `200`:

```json
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 604800,
  "guestId": "0b8fa94c-09a7-49c9-a8a7-8a38f733f8d5"
}
```

Поля:

| Поле | Тип | Описание |
|---|---|---|
| `accessToken` | string | guest JWT |
| `tokenType` | string | всегда `Bearer` |
| `expiresIn` | number | TTL access token в секундах |
| `guestId` | UUID | идентификатор guest principal |

### POST /api/auth/register

Создаёт пользователя с ролью `USER`, статусом `ACTIVE`, тарифом `FREE` и начальным балансом по
квоте тарифа `FREE`.

Доступ: открыт.

Запрос:

```json
{
  "email": "user@example.com",
  "password": "password123",
  "name": "Ivan",
  "consentToProcessing": true
}
```

Валидация:

| Поле | Обязательность | Правила |
|---|---:|---|
| `email` | да | `@Email`, `@NotBlank`, email должен быть уникальным без учёта регистра |
| `password` | да | `@Size(min=8,max=100)` |
| `name` | нет | строка или `null` |
| `consentToProcessing` | да | должно быть `true` |

Ответ `201`:

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "7IOz...",
  "tokenType": "Bearer",
  "expiresIn": 1800,
  "user": {
    "id": 1,
    "email": "user@example.com",
    "name": "Ivan",
    "role": "USER",
    "status": "ACTIVE"
  }
}
```

Гостевые сканы при регистрации не привязываются к новому пользователю.
После commit регистрации welcome-письмо асинхронно записывается в durable mail outbox. Ошибка SMTP
не откатывает созданного пользователя.

### POST /api/auth/password/forgot

Создаёт одноразовую ссылку восстановления/первичной установки пароля и ставит письмо в durable
mail outbox. Доступ: открыт.

```json
{
  "email": "user@example.com",
  "locale": "ru"
}
```

Всегда отвечает `202 Accepted` без тела — как для существующего, так и для неизвестного email.
Это не позволяет использовать endpoint для проверки существования аккаунта. Ссылка действует 30
минут; в БД хранится только SHA-256 hash токена.

### POST /api/auth/password/reset

Устанавливает новый пароль по одноразовому reset token. Подходит и OAuth-only аккаунту без пароля.

```json
{
  "token": "opaque-token-from-email",
  "newPassword": "new-password-123"
}
```

Ответ `204`. При успехе token помечается использованным, а все активные refresh-токены пользователя
отзываются. Невалидная, использованная или просроченная ссылка возвращает `400`.

### POST /api/auth/login

Вход по email и паролю.

Доступ: открыт.

Запрос:

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

Валидация:

| Поле | Правила |
|---|---|
| `email` | `@Email`, `@NotBlank` |
| `password` | `@NotBlank` |

Ответ `200`: `AuthResponse`, такой же как у регистрации.

Ошибки:

- `400`, если email/пароль неверные;
- `400`, если учётная запись `BLOCKED` или `DELETED`.

При успешном login обновляется `lastLoginAt`.

### POST /api/auth/refresh

Ротирует refresh token: старый refresh token отзывается, создаётся новая пара access/refresh.

Доступ: открыт.

Запрос:

```json
{
  "refreshToken": "7IOz..."
}
```

Валидация:

| Поле | Правила |
|---|---|
| `refreshToken` | `@NotBlank` |

Ответ `200`: `AuthResponse`.

Ошибки:

- `400`, если refresh token не найден, уже отозван или просрочен;
- `400`, если пользователь refresh token больше не существует.

Refresh token хранится в БД как SHA-256 hash, не в plain text.

### POST /api/auth/logout

Отзывает refresh token. Идемпотентен: неизвестный refresh token молча игнорируется.

Доступ: открыт.

Запрос:

```json
{
  "refreshToken": "7IOz..."
}
```

Ответ `204`, тело отсутствует.

### GET /api/auth/me

Возвращает текущий principal. Если токена нет или он невалиден, endpoint не падает и отвечает
`authenticated=false`.

Доступ: открыт.

Пример без токена:

```json
{
  "authenticated": false,
  "principalType": null,
  "user": null,
  "guestId": null
}
```

Пример с guest token:

```json
{
  "authenticated": true,
  "principalType": "GUEST",
  "user": null,
  "guestId": "0b8fa94c-09a7-49c9-a8a7-8a38f733f8d5"
}
```

Пример с user/admin token:

```json
{
  "authenticated": true,
  "principalType": "USER",
  "user": {
    "id": 1,
    "email": "user@example.com",
    "name": "Ivan",
    "role": "USER",
    "status": "ACTIVE"
  },
  "guestId": null
}
```

## Free Scan API

### POST /api/free-scans

Запускает бесплатный маркетинговый скан. Несмотря на продуктовую публичность flow, HTTP endpoint
требует валидный JWT. Для анонимного пользователя сначала нужно вызвать `POST /api/auth/guest`.

Доступ: `GUEST`, `USER`, `ADMIN`.

Запрос:

```json
{
  "siteUrl": "example.ru",
  "jurisdiction": "RU"
}
```

Валидация:

| Поле | Правила |
|---|---|
| `siteUrl` | `@NotBlank`, http/https URL или домен без схемы |
| `jurisdiction` | `@NotBlank`, одно из `RU`, `EU`, `GM` |

Ответ `202`:

```json
{
  "id": "a97f72f4-f333-4d79-a0fb-b063118e1af4",
  "status": "QUEUED",
  "progressStep": "Ожидание",
  "progressPct": 0,
  "reportUrl": null,
  "errorMessage": null
}
```

Побочные эффекты:

- создаётся строка `ComplianceScan`;
- создаётся outbox-событие `ScanRequestedEvent`;
- баланс не меняется.

## Compliance Scans API

### GET /api/compliance-scans

Возвращает историю сканов текущего пользователя. Для guest principal недоступно.

Доступ: `USER`, `ADMIN`.

Query parameters:

| Параметр | Тип | По умолчанию | Описание |
|---|---|---:|---|
| `domain` | string | `null` | фильтр по домену; пустая строка считается отсутствием фильтра |
| `status` | `ScanStatus` | `null` | фильтр по статусу |
| `page` | int | `0` | отрицательное значение приводится к `0` |
| `size` | int | `20` | приводится к диапазону `1..100` |

Пример:

```http
GET /api/compliance-scans?domain=example.ru&status=COMPLETED&page=0&size=20
Authorization: Bearer <userAccessToken>
```

Ответ `200`:

```json
{
  "items": [
    {
      "id": "a97f72f4-f333-4d79-a0fb-b063118e1af4",
      "siteUrl": "https://example.ru",
      "siteDomain": "example.ru",
      "status": "COMPLETED",
      "score": 72,
      "tier": "PREMIUM",
      "criticalCount": 0,
      "highCount": 1,
      "parentScanId": null,
      "createdAt": "2026-06-17T12:00:00Z",
      "finishedAt": "2026-06-17T12:01:30Z"
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1
}
```

### GET /api/compliance-scans/{id}

Возвращает статус и прогресс скана.

Доступ: владелец скана (`userId` или `guestId`).

Path parameters:

| Параметр | Тип |
|---|---|
| `id` | UUID |

Ответ `200`:

```json
{
  "id": "a97f72f4-f333-4d79-a0fb-b063118e1af4",
  "status": "CRAWLING",
  "progressStep": "Обход страниц сайта",
  "progressPct": 35,
  "reportUrl": null,
  "errorMessage": null
}
```

Когда статус терминальный и не `FAILED`, `reportUrl` заполняется:

```json
{
  "id": "a97f72f4-f333-4d79-a0fb-b063118e1af4",
  "status": "COMPLETED",
  "progressStep": "Готово",
  "progressPct": 100,
  "reportUrl": "/api/compliance-scans/a97f72f4-f333-4d79-a0fb-b063118e1af4/report",
  "errorMessage": null
}
```

### GET /api/compliance-scans/{id}/report

Возвращает отчёт скана из snapshot JSON, сохранённого worker'ом.

Доступ: владелец скана.

Ответ `200`:

```json
{
  "id": "a97f72f4-f333-4d79-a0fb-b063118e1af4",
  "siteUrl": "https://example.ru",
  "siteDomain": "example.ru",
  "status": "COMPLETED",
  "score": 72,
  "tier": "PREMIUM",
  "parentScanId": null,
  "summary": {
    "critical": 0,
    "high": 1,
    "medium": 2,
    "low": 0,
    "totalPotentialFine": "от 150 000 до 500 000 ₽",
    "sanctionExposure": {
      "headline": "От 150 000 до 500 000 ₽ — суммарно по потенциальным нарушениям",
      "minimumRelevantAmount": 150000,
      "maximumRelevantAmount": 500000,
      "currency": "RUB",
      "calculationMethod": "SUM_DISTINCT_VIOLATION_GROUP_RANGES",
      "scenariosAreNotSummed": false,
      "requiresLegalQualification": true,
      "scenarios": [
        {
          "id": "KOAP_13_11_1_LEGAL_FIRST",
          "aggregationGroup": "GENERAL_PROCESSING",
          "label": "обработка без предусмотренного законом основания",
          "relatedFindingCodes": ["THIRD_PARTY_TRACKERS"],
          "law": "КоАП РФ",
          "article": "13.11",
          "part": "1",
          "subjectType": "Юридическое лицо",
          "recurrence": "FIRST",
          "minimumAmount": 150000,
          "maximumAmount": 300000,
          "currency": "RUB",
          "applicability": "Только если проверка подтвердит обработку ПДн без применимого основания.",
          "sourceUrl": "https://www.consultant.ru/document/cons_doc_LAW_34661/1f421640c6775ff67079ebde06a7d2f6d17b96db/",
          "normVerifiedOn": "2026-07-13"
        },
        {
          "id": "KOAP_13_11_1_1_LEGAL_REPEAT",
          "aggregationGroup": "GENERAL_PROCESSING",
          "label": "повторная обработка без предусмотренного законом основания",
          "relatedFindingCodes": ["THIRD_PARTY_TRACKERS"],
          "law": "КоАП РФ",
          "article": "13.11",
          "part": "1.1",
          "subjectType": "Юридическое лицо или ИП",
          "recurrence": "REPEATED",
          "minimumAmount": 300000,
          "maximumAmount": 500000,
          "currency": "RUB",
          "applicability": "Только при подтверждении состава и юридически установленной повторности.",
          "sourceUrl": "https://www.consultant.ru/document/cons_doc_LAW_34661/1f421640c6775ff67079ebde06a7d2f6d17b96db/",
          "normVerifiedOn": "2026-07-13"
        }
      ]
    }
  },
  "findings": [
    {
      "code": "THIRD_PARTY_TRACKERS",
      "severity": "HIGH",
      "category": "DOCUMENTS",
      "title": "Обнаружена обработка через сторонние трекеры",
      "fineAmount": "от 150 000 до 500 000 ₽",
      "legalBasis": "ст. 13.11 ч. 1 и 1.1 КоАП РФ",
      "explanation": "На сайте обнаружены сторонние трекеры, требующие проверки правового основания.",
      "recommendation": "Проверьте основание обработки, получателей и раскрытие в документах.",
      "evidence": "third-party tracker request observed",
      "sourceUrl": "https://example.ru",
      "sourceType": "HTML",
      "confidence": 0.8,
      "verificationStatus": "DETECTED",
      "evidenceType": "STATIC_ANALYSIS",
      "matchedSignals": ["tracker.example"],
      "affectedPages": [
        {
          "url": "https://example.ru",
          "evidence": "third-party tracker request observed",
          "sourceType": "HTML",
          "confidence": 0.8,
          "verificationStatus": "DETECTED",
          "evidenceType": "STATIC_ANALYSIS",
          "matchedSignals": ["tracker.example"]
        }
      ]
    }
  ],
  "diagnostics": {
    "pagesAttempted": 5,
    "pagesFetched": 5,
    "pagesFailed": 0,
    "crawlerTimedOut": false,
    "ruleErrors": []
  },
  "quality": {
    "passed": 8,
    "failed": 3,
    "notEvaluated": 0,
    "coveragePercent": 100,
    "unverifiedRules": [],
    "positiveChecks": [
      {
        "code": "HTTPS_ENABLED",
        "title": "HTTPS включён",
        "category": "SECURITY",
        "message": "Страницы доступны по защищённому соединению."
      }
    ]
  },
  "paywallCta": null,
  "durationMs": 90000,
  "createdAt": "2026-06-17T12:00:00Z",
  "finishedAt": "2026-06-17T12:01:30Z"
}
```

`score` — индекс наблюдаемого риска внешнего контура, а не процент полного соответствия 152-ФЗ.
`UNVERIFIED`, `FALSE_POSITIVE` и findings без verification status не уменьшают `score` и не входят
в severity-сводку. Неполнота проверки отражается в `diagnostics` и `quality`.

`summary.totalPotentialFine` — арифметический диапазон по структурированному каталогу. Для каждой
независимой `aggregationGroup` берутся минимальная и максимальная альтернативы, после чего границы
разных групп складываются. Поэтому ИП/юрлицо и первое/повторное нарушение внутри одного состава не
удваивают итог. Свободный текст `fineAmount` в расчёте не участвует и для `UNVERIFIED` не выдаётся.

`summary.sanctionExposure` использует метод `SUM_DISTINCT_VIOLATION_GROUP_RANGES` и содержит
машиночитаемые итоговые границы. В FREE `scenarios` — пустой массив (headline и итоговый диапазон
используются как paywall pain), в PREMIUM возвращаются группы, части КоАП, субъект, повторность и
условия применимости.

Если отчёт ещё не готов, возвращается `409 Conflict`.

Free-отчёт:

- читает `freeReportJson`;
- получает `paywallCta` из конфигурации `compliance.paywall-cta`;
- premium-поля findings (`explanation`, `recommendation`, `evidence`, `sourceUrl`) ожидаемо могут
  быть `null`, потому что masking выполняет worker при построении snapshot.

Premium-отчёт:

- читается из `premiumReportJson`;
- выбирается если `scan.tier == PREMIUM` или `scan.kind == CABINET_PREMIUM`;
- `paywallCta = null`.

### POST /api/compliance-scans/{id}/email

Сохраняет email и согласия для отчёта. После готовности JSON-снапшота асинхронно отправляет
`REPORT_READY` со ссылкой на Next.js-страницу отчёта. PDF-вложения нет: PDF при необходимости
формирует frontend из JSON.

Доступ: владелец скана.

Запрос:

```json
{
  "email": "buyer@example.com",
  "consentToProcessing": true,
  "consentToMarketing": false
}
```

Валидация:

| Поле | Правила |
|---|---|
| `email` | `@Email`, `@NotBlank` |
| `consentToProcessing` | должно быть `true` |
| `consentToMarketing` | boolean |

Ответ `204`, тело отсутствует.

Если `consentToMarketing=true`, адрес создаёт или реактивирует маркетинговую подписку. Значение
`false` не считается глобальной отпиской; для неё используется отдельный unsubscribe flow.

### POST /api/mail/unsubscribe

Публичная идемпотентная отписка по подписанному opaque token. Frontend получает token из ссылки
письма и выполняет POST:

```json
{
  "token": "subscription-id.signature"
}
```

Ответ всегда `204`, включая невалидный/устаревший token, чтобы не раскрывать наличие подписки.

### POST /api/admin/mail/promo

Ставит одиночное promo-письмо в очередь. Доступ: только `ADMIN`. Адрес должен иметь активную
маркетинговую подписку.

```json
{
  "campaignId": "b569af42-da0f-4d87-814f-a3f63229bc47",
  "email": "subscriber@example.com",
  "subject": "Новые возможности OKDOCS",
  "title": "Проверяйте сайты быстрее",
  "body": "Мы обновили отчёты и правила.",
  "actionUrl": "https://okdocs.io/updates",
  "locale": "ru"
}
```

Ответ `202`. Повтор той же пары `campaignId + subscriptionId` идемпотентен. Перед фактической
SMTP-отправкой подписка проверяется повторно; отписанное письмо получает статус `CANCELLED`.

Если `consentToProcessing=false`, вернётся `400`.

## Cabinet API

Все endpoints требуют `USER` или `ADMIN`.

### POST /api/cabinet/scans

Запускает полноценный кабинетный скан со списанием баланса.

Запрос:

```json
{
  "siteUrl": "https://example.ru",
  "jurisdiction": "RU",
  "parentScanId": "a97f72f4-f333-4d79-a0fb-b063118e1af4"
}
```

Валидация:

| Поле | Правила |
|---|---|
| `siteUrl` | `@NotBlank`, http/https URL или домен без схемы |
| `jurisdiction` | `@NotBlank`, одно из `RU`, `EU`, `GM` |
| `parentScanId` | UUID или `null`; если указан, должен принадлежать пользователю и иметь тот же домен |

Ответ `202`: `ScanStatusResponse`.

Ошибки:

- `402`, если баланс пуст;
- `400`, если `parentScanId` указывает на скан другого домена;
- `403`, если родительский скан принадлежит другому владельцу;
- `404`, если родительский скан не найден.

### GET /api/cabinet

Возвращает read-модель кабинета.

Ответ `200`:

```json
{
  "user": {
    "id": 1,
    "email": "user@example.com",
    "name": "Ivan",
    "role": "USER",
    "status": "ACTIVE"
  },
  "plan": "FREE",
  "planRenewsAt": "2026-07-17T12:00:00Z",
  "balance": {
    "monthlyQuota": 0,
    "usedThisPeriod": 0,
    "purchasedRemaining": 0,
    "available": 0,
    "periodResetAt": "2026-07-17T12:00:00Z"
  },
  "totalScans": 3,
  "recentScans": []
}
```

### GET /api/cabinet/balance

Возвращает баланс сканов текущего пользователя.

Ответ `200`:

```json
{
  "monthlyQuota": 30,
  "usedThisPeriod": 4,
  "purchasedRemaining": 0,
  "available": 26,
  "periodResetAt": "2026-07-17T12:00:00Z"
}
```

Формула:

```text
available = monthlyQuota - usedThisPeriod + purchasedRemaining
```

`purchasedRemaining` хранит докупленные сканы и ручные админские корректировки; при списании месячная
квота расходуется первой, затем `purchasedRemaining`.

### GET /api/cabinet/balance/transactions

Возвращает леджер баланса текущего пользователя, отсортированный по `createdAt DESC`.

Query parameters:

| Параметр | Тип | По умолчанию | Описание |
|---|---|---:|---|
| `page` | int | `0` | отрицательное значение приводится к `0` |
| `size` | int | `20` | приводится к диапазону `1..100` |

Ответ `200`:

```json
[
  {
    "id": "f7094104-a353-4b55-91f0-75442b349ec4",
    "type": "DEBIT",
    "source": "MONTHLY",
    "amount": -1,
    "balanceAfter": 25,
    "scanId": "a97f72f4-f333-4d79-a0fb-b063118e1af4",
    "siteDomain": "example.ru",
    "note": null,
    "createdAt": "2026-06-17T12:00:00Z"
  }
]
```

Возвращается только массив элементов, без `page/size/total`.

### POST /api/cabinet/password

Меняет пароль текущего пользователя и отзывает все его активные refresh tokens.

Запрос:

```json
{
  "oldPassword": "password123",
  "newPassword": "newPassword123"
}
```

Валидация:

| Поле | Правила |
|---|---|
| `oldPassword` | `@NotBlank` |
| `newPassword` | `@Size(min=8,max=100)` |

Ответ `204`.

Ошибки:

- `400`, если старый пароль неверен.

## Payments API

Платёж покупает один из двух типов продукта; webhook оплаты НЕ запускает скан:

- **`ONE_REPORT`** (Balance-first top-up) — пополняет `purchasedRemaining` на 1 кредит. Premium-скан
  юзер запускает отдельно через `POST /api/cabinet/scans` (списание 1 кредита).
- **`PRO`/`BUSINESS`** (paid plan) — активирует тариф аккаунта на **30 дней**: `app_users.plan`,
  `plan_renews_at = now+30d` (конец оплаченного периода), месячная квота через `grantMonthly`.
  Модель **разовая, без auto-renew и без proration**: продление — только новой оплатой; по истечении
  `MonthlyQuotaScheduler` переводит юзера на FREE. `purchased_remaining` от ONE_REPORT при истечении
  тарифа НЕ сгорает.

Правила тарифов: upgrade `PRO→BUSINESS` в активном периоде — сразу (старые дни сгорают); downgrade
`BUSINESS→PRO` в активном периоде — **запрещён** (`400` при создании платежа); повторная покупка того
же тарифа — перезапись `now+30d`.

Каталог продуктов отдаёт существующий `GET /api/pricing/plans` (публичный).

Провайдер выбирается роутером по `locale` (+ опционально явному `provider`):

| locale | доступные провайдеры (этой итерации) |
|---|---|
| `ru` | `YOOKASSA` |

Неподдерживаемая комбинация locale/provider → `400`.

### POST /api/payments

Создаёт платёж (top-up или тариф — по `productCode`). Требует роль `USER`; `userId` берётся из JWT.
Алиас `POST /api/payments/balance` оставлен deprecated на 1–2 релиза (то же поведение).

Запрос:

```json
{
  "productCode": "PRO",
  "provider": "YOOKASSA",
  "locale": "ru",
  "returnUrl": "https://app.example/payment/success"
}
```

| Поле | Тип | Правила |
|---|---|---|
| `productCode` | `PricingPlanCode` | `@NotNull`; `ONE_REPORT` (top-up), `PRO`/`BUSINESS` (тариф) |
| `provider` | `PaymentProvider` | опционально; `null` → дефолт по locale. Enum: `YOOKASSA`, `STRIPE`, `TELEGRAM`, `TON` (реализован только `YOOKASSA`) |
| `locale` | string | опционально; дефолт `ru` |
| `returnUrl` | string | опционально; host должен быть в allowlist `yookassa.allowed-return-hosts`, иначе `400`. Пусто → дефолт магазина |

Ответ `200`:

```json
{
  "paymentPublicId": "b1d2...",
  "provider": "YOOKASSA",
  "providerPaymentId": "2f8c...",
  "confirmationUrl": "https://yoomoney.ru/checkout/payments/...",
  "status": "PENDING",
  "productCode": "ONE_REPORT",
  "credits": 1,
  "amount": 990.00,
  "currency": "RUB",
  "expiresAt": "2026-06-28T13:00:00Z"
}
```

Фронт редиректит пользователя на `confirmationUrl`.

Ошибки:

- `400`, если продукт недоступен для покупки (или его `billingPeriod` не согласован с типом:
  ONE_REPORT должен быть `ONE_TIME`, PRO/BUSINESS — `MONTH`), downgrade `BUSINESS→PRO` в активном
  периоде, провайдер недоступен для locale, у юзера нет email (требуется для фискального чека
  YooKassa), `returnUrl` указывает на хост не из allowlist, или вызов create у провайдера завершился
  ошибкой.

Создание платежа НЕ держит транзакцию во время внешнего вызова провайдера: сессия фиксируется в
`CREATED` (commit), затем вызывается провайдер, затем сессия переводится в `PENDING` (commit) — иначе
при откате локального commit'а после успешного create у провайдера получился бы orphan-платёж.

Если результат create НЕОПРЕДЕЛЁН (timeout/разрыв — платёж мог реально создаться у провайдера),
сессия помечается **non-terminal** `CREATE_FAILED` (а не terminal `FAILED`): пришедший позже webhook
по `paymentPublicId` под блокировкой запишет `providerPaymentId`, переведёт сессию в `PENDING` и
проведёт активацию (recovery-path). Terminal `FAILED` иначе заставил бы terminal-guard проигнорировать
такой webhook, и оплаченный платёж остался бы без начисления.

### GET /api/payments/{publicId}/status

Возвращает статус платежа. Только владелец (`USER`, owner-check по `userId`). Для `PENDING` сервис
освежает статус у провайдера и при подтверждении пополняет баланс (готовит pull/reconciliation-путь).

Ответ `200`:

```json
{
  "paymentPublicId": "b1d2...",
  "provider": "YOOKASSA",
  "providerPaymentId": "2f8c...",
  "status": "SUCCEEDED",
  "productCode": "ONE_REPORT",
  "credits": 1,
  "paidAt": "2026-06-28T12:10:00Z",
  "canceledAt": null,
  "failureReason": null
}
```

Ошибки: `403`, если платёж принадлежит другому пользователю; `400`, если не найден.

### POST /api/payments/webhooks/yookassa

Публичный (у провайдера нет JWT). Защита двухуровневая:

1. fail-closed shared-secret из header `X-Webhook-Secret` (= `compliance.payment.webhook-secret`);
   не задан → отвергаются все запросы;
2. сервис перепроверяет факт оплаты у провайдера (`GET /payments/{id}`) и сверяет
   сумму/валюту/`providerPaymentId` перед пополнением — webhook лишь триггер, не источник правды.

Обработка **идемпотентна** на трёх уровнях: terminal-guard до блокировки, lock строки платежа,
unique-индекс `PURCHASE(payment_id)` в леджере. Всегда отвечает `200` на принятый запрос (`401` — на
неверный секрет), чтобы провайдер не зацикливал доставку.

После `SUCCEEDED` баланс пополняется на `credits`; пользователь затем сам запускает скан через
`POST /api/cabinet/scans`.

## Admin API

Все endpoints требуют `ADMIN`. Все мутации пишут `admin_audit_log` в той же транзакции.

### GET /api/admin/users

Возвращает список пользователей.

Query parameters:

| Параметр | Тип | По умолчанию |
|---|---|---:|
| `plan` | `UserPlan` | `null` |
| `status` | `UserStatus` | `null` |
| `page` | int | `0` |
| `size` | int | `20`, максимум `100` |

Пример:

```http
GET /api/admin/users?plan=PRO&status=ACTIVE&page=0&size=20
Authorization: Bearer <adminAccessToken>
```

Ответ `200`:

```json
{
  "items": [
    {
      "id": 1,
      "email": "user@example.com",
      "name": "Ivan",
      "plan": "PRO",
      "status": "ACTIVE",
      "available": 29,
      "totalScans": 10,
      "createdAt": "2026-06-01T10:00:00Z",
      "lastLoginAt": "2026-06-17T11:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1
}
```

### GET /api/admin/users/{id}

Возвращает карточку пользователя: базовая строка, баланс, 10 последних транзакций и 10 последних
сканов.

Ответ `200`:

```json
{
  "user": {
    "id": 1,
    "email": "user@example.com",
    "name": "Ivan",
    "plan": "PRO",
    "status": "ACTIVE",
    "available": 29,
    "totalScans": 10,
    "createdAt": "2026-06-01T10:00:00Z",
    "lastLoginAt": "2026-06-17T11:00:00Z"
  },
  "balance": {
    "monthlyQuota": 30,
    "usedThisPeriod": 1,
    "purchasedRemaining": 0,
    "available": 29,
    "periodResetAt": "2026-07-01T10:00:00Z"
  },
  "recentTransactions": [],
  "recentScans": []
}
```

Если пользователь не найден, текущий сервис возвращает `400`, а не `404`.

### GET /api/admin/users/{id}/scans

Возвращает сканы указанного пользователя без owner-check.

Query parameters:

| Параметр | Тип | По умолчанию |
|---|---|---:|
| `page` | int | `0` |
| `size` | int | `20`, максимум `100` |

Ответ `200`: `ScanListResponse`.

### POST /api/admin/users/{id}/balance

Ручная корректировка баланса. Значение `amount` может быть положительным или отрицательным и
пишется в `purchasedRemaining`.

Запрос:

```json
{
  "userId": 1,
  "amount": 5,
  "reason": "Компенсация"
}
```

Валидация:

| Поле | Правила |
|---|---|
| `userId` | `@NotNull`, должен совпадать с `{id}` в path |
| `amount` | int |
| `reason` | `@NotBlank` |

Ответ `204`.

Audit action: `ADJUST_BALANCE`, `detailsJson={"amount":5}`.

### POST /api/admin/users/{id}/plan

Меняет тариф пользователя, ставит новый `planRenewsAt` на `now + 30 days` и выдаёт месячную квоту
нового тарифа.

Запрос:

```json
{
  "userId": 1,
  "plan": "PRO",
  "reason": "Оплата вручную"
}
```

Валидация:

| Поле | Правила |
|---|---|
| `userId` | `@NotNull`, должен совпадать с `{id}` |
| `plan` | `@NotNull`, одно из `FREE`, `PRO`, `BUSINESS` |
| `reason` | `@NotBlank` |

Ответ `204`.

Audit action: `SET_PLAN`, details содержат `oldPlan` и `newPlan`.

### POST /api/admin/users/{id}/block

Блокирует или разблокирует пользователя. Заблокированный пользователь перестаёт проходить JWT-фильтр:
статус аккаунта проверяется на каждом запросе с user/admin token.

Запрос:

```json
{
  "userId": 1,
  "block": true,
  "reason": "Abuse"
}
```

Валидация:

| Поле | Правила |
|---|---|
| `userId` | `@NotNull`, должен совпадать с `{id}` |
| `block` | boolean |
| `reason` | `@NotBlank` |

Ответ `204`.

Audit action:

- `BLOCK_USER`, если `block=true`;
- `UNBLOCK_USER`, если `block=false`.

### GET /api/admin/stats

Возвращает агрегированную статистику.

Ответ `200`:

```json
{
  "totalUsers": 100,
  "activeUsers": 95,
  "blockedUsers": 5,
  "scansToday": 12,
  "scansTotal": 1234,
  "usersByPlan": {
    "FREE": 80,
    "PRO": 18,
    "BUSINESS": 2
  }
}
```

`scansToday` считается от `Instant.now().truncatedTo(DAYS)`.

### GET /api/admin/audit

Возвращает журнал admin-действий.

Query parameters:

| Параметр | Тип | По умолчанию | Описание |
|---|---|---:|---|
| `targetUserId` | Long | `null` | если задан, фильтрует по целевому пользователю |
| `page` | int | `0` | отрицательное значение приводится к `0` |
| `size` | int | `20` | максимум `100` |

Ответ `200`:

```json
{
  "items": [
    {
      "id": "1d102331-0ca1-4a5c-a9f1-22fbeff30cfa",
      "adminUserId": 10,
      "action": "SET_PLAN",
      "targetUserId": 1,
      "reason": "Оплата вручную",
      "detailsJson": "{\"oldPlan\":\"FREE\",\"newPlan\":\"PRO\"}",
      "createdAt": "2026-06-17T12:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1
}
```

## DTO reference

### Auth DTO

#### GuestAuthResponse

| Поле | Тип |
|---|---|
| `accessToken` | string |
| `tokenType` | string |
| `expiresIn` | long |
| `guestId` | UUID |

#### AuthResponse

| Поле | Тип |
|---|---|
| `accessToken` | string |
| `refreshToken` | string |
| `tokenType` | string |
| `expiresIn` | long |
| `user` | `UserProfileDto` |

#### AuthMeResponse

| Поле | Тип |
|---|---|
| `authenticated` | boolean |
| `principalType` | `PrincipalType` или `null` |
| `user` | `UserProfileDto` или `null` |
| `guestId` | UUID или `null` |

#### UserProfileDto

| Поле | Тип |
|---|---|
| `id` | Long |
| `email` | string |
| `name` | string или `null` |
| `role` | `UserRole` |
| `status` | `UserStatus` |

### Scan DTO

#### ScanStatusResponse

| Поле | Тип | Описание |
|---|---|---|
| `id` | UUID | scan id |
| `status` | `ScanStatus` | текущий статус |
| `progressStep` | string | человекочитаемый этап |
| `progressPct` | int | процент прогресса |
| `reportUrl` | string или `null` | ссылка на report для terminal non-failed статусов |
| `errorMessage` | string или `null` | ошибка failed-скана |

#### ScanListResponse

| Поле | Тип |
|---|---|
| `items` | array of `ScanListItemDto` |
| `page` | int |
| `size` | int |
| `total` | long |

#### ScanListItemDto

| Поле | Тип |
|---|---|
| `id` | UUID |
| `siteUrl` | string |
| `siteDomain` | string |
| `status` | `ScanStatus` |
| `score` | Integer или `null` |
| `tier` | `ScanTier` |
| `criticalCount` | int |
| `highCount` | int |
| `parentScanId` | UUID или `null` |
| `createdAt` | Instant |
| `finishedAt` | Instant или `null` |

#### ScanReportResponse

| Поле | Тип |
|---|---|
| `id` | UUID |
| `siteUrl` | string |
| `siteDomain` | string |
| `status` | `ScanStatus` |
| `score` | Integer или `null` |
| `tier` | `ScanTier` |
| `parentScanId` | UUID или `null` |
| `summary` | `ScanSummaryDto` |
| `findings` | array of `FindingDto` |
| `diagnostics` | `DiagnosticsDto` |
| `quality` | `ReportQualityDto` |
| `paywallCta` | `PaywallCtaDto` или `null` |
| `durationMs` | Long или `null` |
| `createdAt` | Instant |
| `finishedAt` | Instant или `null` |

#### ScanSummaryDto

| Поле | Тип | Примечание |
|---|---|---|
| `critical` | int | Наблюдаемые риски |
| `high` | int | Наблюдаемые риски |
| `medium` | int | Наблюдаемые риски |
| `low` | int | Наблюдаемые риски |
| `totalPotentialFine` | string или `null` | Арифметический диапазон разных групп нарушений, например `от 1 010 000 до 18 060 000 ₽` |
| `sanctionExposure` | `SanctionExposureDto` или `null` | Итоговые границы и сценарии расчёта |

#### SanctionExposureDto

| Поле | Тип | Примечание |
|---|---|---|
| `headline` | string | Маркетинговый headline с арифметическим диапазоном |
| `minimumRelevantAmount` | Long | Сумма минимальных альтернатив разных групп |
| `maximumRelevantAmount` | Long | Сумма максимальных альтернатив разных групп |
| `currency` | string | `RUB` |
| `calculationMethod` | string | `SUM_DISTINCT_VIOLATION_GROUP_RANGES` |
| `scenariosAreNotSummed` | boolean | `false`: разные группы суммируются; альтернативы внутри группы — нет |
| `requiresLegalQualification` | boolean | Всегда `true` для текущего RU-каталога |
| `scenarios` | array of `SanctionScenarioDto` | PREMIUM — детали; FREE — пустой массив |

#### SanctionScenarioDto

| Поле | Тип |
|---|---|
| `id` | string |
| `aggregationGroup` | string |
| `label` | string |
| `relatedFindingCodes` | array of string |
| `law` | string |
| `article` | string |
| `part` | string |
| `subjectType` | string |
| `recurrence` | `FIRST` или `REPEATED` |
| `minimumAmount` | long |
| `maximumAmount` | long |
| `currency` | string |
| `applicability` | string |
| `sourceUrl` | string |
| `normVerifiedOn` | LocalDate |

#### FindingDto

| Поле | Тип |
|---|---|
| `code` | string |
| `severity` | `FindingSeverity` |
| `category` | `FindingCategory` |
| `title` | string |
| `fineAmount` | string или `null` |
| `legalBasis` | string или `null` |
| `explanation` | string или `null` |
| `recommendation` | string или `null` |
| `evidence` | string или `null` |
| `sourceUrl` | string или `null` |
| `sourceType` | `SourceType` или `null` |
| `confidence` | Double или `null` |
| `verificationStatus` | `VerificationStatus` или `null` |
| `evidenceType` | `EvidenceType` или `null` |
| `matchedSignals` | array of string или `null` |
| `affectedPages` | array of `AffectedPageDto` | В PREMIUM — все страницы finding; в FREE — пустой массив. В старых snapshots может быть `null` |

#### AffectedPageDto

| Поле | Тип |
|---|---|
| `url` | string |
| `evidence` | string |
| `sourceType` | `SourceType` |
| `confidence` | Double |
| `verificationStatus` | `VerificationStatus` |
| `evidenceType` | `EvidenceType` |
| `matchedSignals` | array of string |

#### DiagnosticsDto

| Поле | Тип | Примечание |
|---|---|---|
| `pagesAttempted` | int | сколько страниц пытались обработать |
| `pagesFetched` | int | сколько страниц успешно получили |
| `pagesFailed` | int | сколько страниц упало |
| `crawlerTimedOut` | boolean | был ли timeout краулера |
| `ruleErrors` | array of string | ошибки правил |

`ruleOutcomes` в DTO существует, но помечено `@JsonProperty(access = WRITE_ONLY)`, поэтому в JSON
ответа не сериализуется.

#### ReportQualityDto

| Поле | Тип |
|---|---|
| `passed` | int |
| `failed` | int |
| `notEvaluated` | int |
| `positiveChecks` | array of `PositiveCheckDto` |
| `coveragePercent` | integer или `null`; доля правил с однозначным автоматическим результатом |
| `unverifiedRules` | array of `UnverifiedRuleDto`; правила, требующие ручной проверки, и причины |

`UNVERIFIED`-факт не увеличивает `failed`: соответствующее правило учитывается в `notEvaluated`
и уменьшает `coveragePercent`. Процент считается как `(passed + failed) / общее число правил`.

#### UnverifiedRuleDto

| Поле | Тип |
|---|---|
| `code` | string |
| `title` | string |
| `category` | `FindingCategory` |
| `reason` | string или `null` |

#### PositiveCheckDto

| Поле | Тип |
|---|---|
| `code` | string |
| `title` | string |
| `category` | `FindingCategory` |
| `message` | string |

#### PaywallCtaDto

| Поле | Тип |
|---|---|
| `title` | string |
| `text` | string |
| `actionUrl` | string |

### Cabinet DTO

#### ScanBalanceDto

| Поле | Тип |
|---|---|
| `monthlyQuota` | int |
| `usedThisPeriod` | int |
| `purchasedRemaining` | int |
| `available` | int |
| `periodResetAt` | Instant |

#### BalanceTransactionDto

| Поле | Тип |
|---|---|
| `id` | UUID |
| `type` | `BalanceTxnType` |
| `source` | `BalanceTxnSource` (`MONTHLY`/`PURCHASED`) или `null` — карман для DEBIT/REFUND, `null` для остальных типов |
| `amount` | int |
| `balanceAfter` | int |
| `scanId` | UUID или `null` |
| `siteDomain` | string или `null` |
| `note` | string или `null` |
| `createdAt` | Instant |

#### UserDashboardResponse

| Поле | Тип |
|---|---|
| `user` | `UserProfileDto` |
| `plan` | `UserPlan` |
| `planRenewsAt` | Instant |
| `balance` | `ScanBalanceDto` |
| `totalScans` | long |
| `recentScans` | array of `ScanListItemDto` |

### Admin DTO

#### AdminUserListResponse

| Поле | Тип |
|---|---|
| `items` | array of `AdminUserListItem` |
| `page` | int |
| `size` | int |
| `total` | long |

#### AdminUserListItem

| Поле | Тип |
|---|---|
| `id` | Long |
| `email` | string |
| `name` | string или `null` |
| `plan` | `UserPlan` |
| `status` | `UserStatus` |
| `available` | int |
| `totalScans` | long |
| `createdAt` | Instant |
| `lastLoginAt` | Instant или `null` |

#### AdminUserDetail

| Поле | Тип |
|---|---|
| `user` | `AdminUserListItem` |
| `balance` | `ScanBalanceDto` |
| `recentTransactions` | array of `BalanceTransactionDto` |
| `recentScans` | array of `ScanListItemDto` |

#### AdminStatsResponse

| Поле | Тип |
|---|---|
| `totalUsers` | long |
| `activeUsers` | long |
| `blockedUsers` | long |
| `scansToday` | long |
| `scansTotal` | long |
| `usersByPlan` | object map: `UserPlan` -> long |

#### AdminAuditLogResponse

| Поле | Тип |
|---|---|
| `items` | array of `AdminAuditLogDto` |
| `page` | int |
| `size` | int |
| `total` | long |

#### AdminAuditLogDto

| Поле | Тип |
|---|---|
| `id` | UUID |
| `adminUserId` | Long |
| `action` | `AdminActionType` |
| `targetUserId` | Long или `null` |
| `reason` | string |
| `detailsJson` | string или `null` |
| `createdAt` | Instant |

## Enums

### ScanStatus

```text
QUEUED, CRAWLING, ANALYZING, COMPLETED, PARTIAL, FAILED
```

Терминальные статусы: `COMPLETED`, `PARTIAL`, `FAILED`.

### ScanTier

```text
FREE, PREMIUM
```

`FREE` скрывает premium-поля отчёта. `PREMIUM` раскрывает полный snapshot.

### ScanKind

```text
FREE_MARKETING, CABINET_PREMIUM
```

`ScanKind` определяет режим выполнения worker'а. `ScanTier` определяет уровень раскрытия отчёта
при чтении.

### ScanJurisdiction

```text
RU, EU, GM
```

### FindingSeverity

```text
CRITICAL, HIGH, MEDIUM, LOW
```

### FindingCategory

```text
DOCUMENTS, FORMS, CONSENT, COOKIES, TRACKERS, HOSTING, SECURITY, OTHER
```

### SourceType

```text
HTML, INLINE_SCRIPT
```

### EvidenceType

```text
STATIC_ANALYSIS, DYNAMIC_RENDER
```

### VerificationStatus

```text
DETECTED, UNVERIFIED, CONFIRMED, FALSE_POSITIVE
```

### PrincipalType

```text
GUEST, USER, ADMIN
```

### UserRole

```text
USER, ADMIN
```

### UserStatus

```text
ACTIVE, BLOCKED, DELETED
```

### UserPlan

```text
FREE, PRO, BUSINESS
```

### BalanceTxnType

```text
PLAN_GRANT, PURCHASE, DEBIT, REFUND, ADMIN_ADJUST, EXPIRE
```

### AdminActionType

```text
ADJUST_BALANCE, SET_PLAN, BLOCK_USER, UNBLOCK_USER
```

## Kafka/outbox side effects

HTTP-запуск скана не публикует Kafka напрямую. API создаёт `OutboxEvent` в той же транзакции, что
и строку скана, а `OutboxPublisher` из `compliance-messaging` публикует событие в Kafka.

При запуске free/cabinet scan создаётся `ScanRequestedEvent`:

```json
{
  "eventId": "a4d99d90-6632-4f48-9eee-50b0927ef15a",
  "schemaVersion": 1,
  "scanId": "a97f72f4-f333-4d79-a0fb-b063118e1af4",
  "userId": 1,
  "guestId": null,
  "siteUrl": "https://example.ru",
  "requestedAt": "2026-06-17T12:00:00Z"
}
```

Режим выполнения (`kind`, `maxPages`, `dynamicRequired`, `tier`) не передаётся в событии. Worker
читает эти значения из `ComplianceScan` в БД.

## Конфигурация модуля

Основной prefix: `compliance`.

```yaml
compliance:
  kafka:
    topic:
      scan-requested: compliance.scan.requested
      scan-completed: compliance.scan.completed
      scan-failed: compliance.scan.failed

  rate-limit:
    guest-scans-per-ip-per-hour: 5
    user-scans-per-hour: 20

  scan:
    free-marketing-max-pages: 1
    guest-max-pages: 5
    user-max-pages: 100
    guest-retention-days: 7
    free-marketing-retention-days: 7

  plan:
    quota:
      FREE: 0
      PRO: 30
      BUSINESS: 200

  paywall-cta:
    title: "Откройте полный отчёт"
    text: "Получите доказательства, ссылки на закон, рекомендации и PDF-отчёт"
    action-url: "/payment"

  auth:
    jwt-secret: ${JWT_SECRET:change-me-in-production-this-must-be-at-least-256-bits-long-secret-key}
    access-token-ttl: 30m
    refresh-token-ttl: 30d
    guest-token-ttl: 7d

  security:
    trust-forwarded-header: false
```

Связанные Spring properties:

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/compliance}
    username: ${DB_USERNAME:compliance}
    password: ${DB_PASSWORD:compliance}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      username: ${REDIS_USERNAME:}
      password: ${REDIS_PASSWORD:}
      ssl:
        enabled: ${REDIS_SSL_ENABLED:false}
```

Actuator:

```text
GET /actuator/health
GET /actuator/info
GET /actuator/metrics
GET /actuator/prometheus
```

В `SecurityConfig` открыты только `/actuator/health/**` и `/actuator/info`; остальные actuator
endpoints требуют аутентификации, если не переопределить security.

## Локальный запуск

Инфраструктура:

```bash
docker-compose up -d
```

Standalone API:

```bash
mvn spring-boot:run -pl compliance-api
```

Локальный combined app (`api + worker` в одном процессе):

```bash
mvn spring-boot:run -pl compliance-app -Dspring-boot.run.profiles=local
```

Локальные URLs:

```text
API:        http://localhost:8080
Health:     http://localhost:8080/actuator/health
Kafka UI:   http://localhost:8090
```

## Что не является API текущего compliance-api

В текущих контроллерах `compliance-api` нет следующих endpoints:

- `POST /api/compliance-scans` для запуска скана; запуск разделён на
  `POST /api/free-scans` и `POST /api/cabinet/scans`;
- `POST /api/compliance-scans/{id}/payment`;
- `GET /api/compliance-scans/sample-report`;
- `POST /api/compliance-scans/promo/validate`;
- endpoints scheduled scans/monthly monitoring;
- endpoint отмены скана.

DTO-заделы для части этих фич могут существовать в `compliance-contracts`, но HTTP-методов в
текущем `compliance-api` нет.
