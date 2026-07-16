CREATE TABLE remediation_leads (
    id             UUID          PRIMARY KEY,
    site_url       VARCHAR(2048) NOT NULL,
    site_domain    VARCHAR(255)  NOT NULL,
    contact_name   VARCHAR(100)  NOT NULL,
    contact_email  VARCHAR(254)  NOT NULL,
    contact_phone  VARCHAR(40),
    locale         VARCHAR(16)   NOT NULL,
    status         VARCHAR(30)   NOT NULL,
    consent_at     TIMESTAMP     NOT NULL,
    ip_address     VARCHAR(45)   NOT NULL,
    created_at     TIMESTAMP     NOT NULL,
    updated_at     TIMESTAMP     NOT NULL,
    CONSTRAINT chk_remediation_lead_status CHECK (
        status IN ('NEW', 'CONTACTED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT chk_remediation_lead_locale CHECK (locale IN ('ru', 'en'))
);

-- Пока заявка в работе, повтор формы или сетевой retry возвращает существующую запись.
-- После COMPLETED/CANCELLED тот же клиент может создать новую заявку.
CREATE UNIQUE INDEX uq_remediation_lead_active_email_domain
    ON remediation_leads (LOWER(contact_email), site_domain)
    WHERE status IN ('NEW', 'CONTACTED', 'IN_PROGRESS');

CREATE INDEX idx_remediation_lead_status_created
    ON remediation_leads (status, created_at);
