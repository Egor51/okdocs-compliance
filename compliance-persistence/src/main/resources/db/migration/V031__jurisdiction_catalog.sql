-- Каталог юрисдикций для публичного фронта. Раньше тексты были захардкожены в
-- JurisdictionCatalogService.buildCatalog(); переносим в БД, чтобы менять SEO/тексты без релиза.
-- Локализуемые поля (H1/H2, SEO, название страны) хранятся по locale в jurisdiction_catalog_translations.
-- Список законов (laws) — упорядоченная дочерняя таблица, как pricing_plan_features.

CREATE TABLE jurisdiction_catalog (
    id                   BIGSERIAL PRIMARY KEY,
    code                 VARCHAR(8)  NOT NULL UNIQUE,
    slug                 VARCHAR(64) NOT NULL UNIQUE,
    active               BOOLEAN     NOT NULL DEFAULT TRUE,
    content_language     VARCHAR(8)  NOT NULL,
    default_jurisdiction BOOLEAN     NOT NULL DEFAULT FALSE,
    sort_order           INT         NOT NULL,
    created_at           TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_jurisdiction_catalog_code CHECK (code IN ('RU', 'EU', 'UK', 'DE', 'FR', 'ES'))
);

CREATE TABLE jurisdiction_catalog_translations (
    id               BIGSERIAL PRIMARY KEY,
    jurisdiction_id  BIGINT       NOT NULL REFERENCES jurisdiction_catalog(id) ON DELETE CASCADE,
    locale           VARCHAR(16)  NOT NULL,
    display_name     VARCHAR(255) NOT NULL,
    description      TEXT         NOT NULL,
    seo_title        VARCHAR(255) NOT NULL,
    seo_description  TEXT         NOT NULL,
    country_name     VARCHAR(120) NOT NULL,
    CONSTRAINT uq_jurisdiction_translation_locale UNIQUE (jurisdiction_id, locale)
);

CREATE TABLE jurisdiction_catalog_laws (
    id               BIGSERIAL PRIMARY KEY,
    jurisdiction_id  BIGINT       NOT NULL REFERENCES jurisdiction_catalog(id) ON DELETE CASCADE,
    text             VARCHAR(120) NOT NULL,
    sort_order       INT          NOT NULL,
    CONSTRAINT uq_jurisdiction_law_order UNIQUE (jurisdiction_id, sort_order)
);

CREATE INDEX idx_jurisdiction_catalog_active_sort ON jurisdiction_catalog (active, sort_order);
CREATE INDEX idx_jurisdiction_translations_locale ON jurisdiction_catalog_translations (locale);

INSERT INTO jurisdiction_catalog (code, slug, content_language, default_jurisdiction, sort_order)
VALUES
    ('RU', '152-fz',  'ru', TRUE,  10),
    ('EU', 'gdpr',    'en', FALSE, 20),
    ('UK', 'uk-gdpr', 'en', FALSE, 30),
    ('DE', 'bdsg',    'en', FALSE, 40),
    ('FR', 'cnil',    'en', FALSE, 50),
    ('ES', 'lopdgdd', 'en', FALSE, 60);

-- ===== RU (152-ФЗ) =====
INSERT INTO jurisdiction_catalog_translations (jurisdiction_id, locale, display_name, description, seo_title, seo_description, country_name)
SELECT id, 'ru',
       'Найдите нарушения 152-ФЗ на вашем сайте и снизьте риск штрафов',
       'Вставьте URL сайта — получите отчёт с нарушениями, возможными штрафами и конкретными шагами исправления.',
       'Проверка сайта на соответствие 152-ФЗ онлайн — отчёт о нарушениях и штрафах',
       'Онлайн-проверка сайта на соответствие 152-ФЗ: нарушения обработки персональных данных, возможные штрафы и пошаговые рекомендации по исправлению.',
       'Российская Федерация'
FROM jurisdiction_catalog WHERE code = 'RU';
INSERT INTO jurisdiction_catalog_translations (jurisdiction_id, locale, display_name, description, seo_title, seo_description, country_name)
SELECT id, 'en',
       'Find 152-FZ violations on your website and reduce the risk of fines',
       'Paste a website URL to get a report with violations, potential fines and concrete remediation steps.',
       'Russian 152-FZ website compliance check — violations and fines report',
       'Online website compliance scan for Russian 152-FZ personal data requirements: violations, potential fines and step-by-step remediation.',
       'Russian Federation'
FROM jurisdiction_catalog WHERE code = 'RU';
INSERT INTO jurisdiction_catalog_laws (jurisdiction_id, text, sort_order)
SELECT id, '152-ФЗ', 10 FROM jurisdiction_catalog WHERE code = 'RU';

-- ===== EU (GDPR) =====
INSERT INTO jurisdiction_catalog_translations (jurisdiction_id, locale, display_name, description, seo_title, seo_description, country_name)
SELECT id, 'ru',
       'Европейский союз',
       'Проверка по GDPR и базовым требованиям ePrivacy для сайтов, работающих с пользователями ЕС.',
       'Проверка сайта на соответствие GDPR онлайн — отчёт о нарушениях',
       'Онлайн-проверка сайта на соответствие GDPR и ePrivacy для компаний, работающих с пользователями ЕС: нарушения и рекомендации.',
       'Европейский союз'
FROM jurisdiction_catalog WHERE code = 'EU';
INSERT INTO jurisdiction_catalog_translations (jurisdiction_id, locale, display_name, description, seo_title, seo_description, country_name)
SELECT id, 'en',
       'European Union',
       'GDPR and baseline ePrivacy compliance scan for websites serving EU users.',
       'GDPR website compliance check online — violations and remediation report',
       'Online GDPR and baseline ePrivacy compliance scan for websites serving EU users: violations, risk score and remediation steps.',
       'European Union'
FROM jurisdiction_catalog WHERE code = 'EU';
INSERT INTO jurisdiction_catalog_laws (jurisdiction_id, text, sort_order)
SELECT id, t.text, t.ord FROM jurisdiction_catalog j,
    (VALUES ('GDPR', 10), ('ePrivacy Directive', 20)) AS t(text, ord)
WHERE j.code = 'EU';

-- ===== UK (UK GDPR / PECR) =====
INSERT INTO jurisdiction_catalog_translations (jurisdiction_id, locale, display_name, description, seo_title, seo_description, country_name)
SELECT id, 'ru',
       'Великобритания',
       'Проверка по UK GDPR и PECR для сайтов, работающих с пользователями Великобритании.',
       'Проверка сайта на соответствие UK GDPR и PECR онлайн',
       'Онлайн-проверка сайта на соответствие UK GDPR и PECR для компаний, работающих с пользователями Великобритании.',
       'Великобритания'
FROM jurisdiction_catalog WHERE code = 'UK';
INSERT INTO jurisdiction_catalog_translations (jurisdiction_id, locale, display_name, description, seo_title, seo_description, country_name)
SELECT id, 'en',
       'United Kingdom',
       'UK GDPR and PECR compliance scan for websites serving UK users.',
       'UK GDPR & PECR website compliance check online',
       'Online UK GDPR and PECR compliance scan for websites serving UK users: violations, risk score and remediation steps.',
       'United Kingdom'
FROM jurisdiction_catalog WHERE code = 'UK';
INSERT INTO jurisdiction_catalog_laws (jurisdiction_id, text, sort_order)
SELECT id, t.text, t.ord FROM jurisdiction_catalog j,
    (VALUES ('UK GDPR', 10), ('PECR', 20)) AS t(text, ord)
WHERE j.code = 'UK';

-- ===== DE (BDSG / TTDSG) =====
INSERT INTO jurisdiction_catalog_translations (jurisdiction_id, locale, display_name, description, seo_title, seo_description, country_name)
SELECT id, 'ru',
       'Германия',
       'Проверка по GDPR с немецким overlay: BDSG и TTDSG.',
       'Проверка сайта на соответствие GDPR, BDSG и TTDSG (Германия)',
       'Онлайн-проверка сайта по GDPR с немецким overlay BDSG и TTDSG: нарушения и рекомендации для компаний из Германии.',
       'Германия'
FROM jurisdiction_catalog WHERE code = 'DE';
INSERT INTO jurisdiction_catalog_translations (jurisdiction_id, locale, display_name, description, seo_title, seo_description, country_name)
SELECT id, 'en',
       'Germany',
       'GDPR compliance scan with Germany-specific BDSG and TTDSG overlay.',
       'Germany GDPR, BDSG & TTDSG website compliance check',
       'Online GDPR compliance scan with Germany-specific BDSG and TTDSG overlay: violations, risk score and remediation steps.',
       'Germany'
FROM jurisdiction_catalog WHERE code = 'DE';
INSERT INTO jurisdiction_catalog_laws (jurisdiction_id, text, sort_order)
SELECT id, t.text, t.ord FROM jurisdiction_catalog j,
    (VALUES ('GDPR', 10), ('BDSG', 20), ('TTDSG', 30)) AS t(text, ord)
WHERE j.code = 'DE';

-- ===== FR (CNIL) =====
INSERT INTO jurisdiction_catalog_translations (jurisdiction_id, locale, display_name, description, seo_title, seo_description, country_name)
SELECT id, 'ru',
       'Франция',
       'Проверка по GDPR с французским overlay и требованиями CNIL.',
       'Проверка сайта на соответствие GDPR и требованиям CNIL (Франция)',
       'Онлайн-проверка сайта по GDPR с французским overlay и требованиями CNIL: нарушения и рекомендации для компаний из Франции.',
       'Франция'
FROM jurisdiction_catalog WHERE code = 'FR';
INSERT INTO jurisdiction_catalog_translations (jurisdiction_id, locale, display_name, description, seo_title, seo_description, country_name)
SELECT id, 'en',
       'France',
       'GDPR compliance scan with France-specific CNIL overlay.',
       'France GDPR & CNIL website compliance check',
       'Online GDPR compliance scan with France-specific CNIL requirements: violations, risk score and remediation steps.',
       'France'
FROM jurisdiction_catalog WHERE code = 'FR';
INSERT INTO jurisdiction_catalog_laws (jurisdiction_id, text, sort_order)
SELECT id, t.text, t.ord FROM jurisdiction_catalog j,
    (VALUES ('GDPR', 10), ('Loi Informatique et Libertés', 20), ('CNIL guidance', 30)) AS t(text, ord)
WHERE j.code = 'FR';

-- ===== ES (LOPDGDD / AEPD) =====
INSERT INTO jurisdiction_catalog_translations (jurisdiction_id, locale, display_name, description, seo_title, seo_description, country_name)
SELECT id, 'ru',
       'Испания',
       'Проверка по GDPR с испанским overlay: LOPDGDD и требования AEPD.',
       'Проверка сайта на соответствие GDPR, LOPDGDD и AEPD (Испания)',
       'Онлайн-проверка сайта по GDPR с испанским overlay LOPDGDD и требованиями AEPD: нарушения и рекомендации.',
       'Испания'
FROM jurisdiction_catalog WHERE code = 'ES';
INSERT INTO jurisdiction_catalog_translations (jurisdiction_id, locale, display_name, description, seo_title, seo_description, country_name)
SELECT id, 'en',
       'Spain',
       'GDPR compliance scan with Spain-specific LOPDGDD and AEPD overlay.',
       'Spain GDPR, LOPDGDD & AEPD website compliance check',
       'Online GDPR compliance scan with Spain-specific LOPDGDD and AEPD requirements: violations, risk score and remediation steps.',
       'Spain'
FROM jurisdiction_catalog WHERE code = 'ES';
INSERT INTO jurisdiction_catalog_laws (jurisdiction_id, text, sort_order)
SELECT id, t.text, t.ord FROM jurisdiction_catalog j,
    (VALUES ('GDPR', 10), ('LOPDGDD', 20), ('AEPD guidance', 30)) AS t(text, ord)
WHERE j.code = 'ES';
