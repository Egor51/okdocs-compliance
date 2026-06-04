-- Этап 2 §2.2 — сканы, findings, согласия на email

CREATE TABLE compliance_scans (
    id               UUID         PRIMARY KEY,
    user_id          BIGINT       REFERENCES app_users(id),
    guest_id         UUID,
    ip_address       VARCHAR(45)  NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    site_url         TEXT         NOT NULL,
    site_domain      VARCHAR(255) NOT NULL,
    started_at       TIMESTAMP,
    finished_at      TIMESTAMP,
    duration_ms      BIGINT,
    progress_step    VARCHAR(100),
    progress_pct     INT          NOT NULL DEFAULT 0,
    score            INT,
    pages_scanned    INT          NOT NULL DEFAULT 0,
    tier             VARCHAR(30)  NOT NULL,
    buyer_email      VARCHAR(255),
    error_message    TEXT,
    diagnostics_json TEXT,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_compliance_scans_user_created   ON compliance_scans(user_id, created_at DESC);
CREATE INDEX idx_compliance_scans_guest_created  ON compliance_scans(guest_id, created_at DESC);
CREATE INDEX idx_compliance_scans_status_created ON compliance_scans(status, created_at);
CREATE INDEX idx_compliance_scans_site_domain    ON compliance_scans(site_domain);
CREATE INDEX idx_compliance_scans_ip_created     ON compliance_scans(ip_address, created_at DESC);

CREATE TABLE compliance_findings (
    id                  UUID         PRIMARY KEY,
    scan_id             UUID         NOT NULL REFERENCES compliance_scans(id) ON DELETE CASCADE,
    code                VARCHAR(60)  NOT NULL,
    severity            VARCHAR(30)  NOT NULL,
    category            VARCHAR(50)  NOT NULL,
    title               TEXT         NOT NULL,
    evidence            TEXT,
    source_url          TEXT,
    source_type         VARCHAR(30),
    fine_amount         VARCHAR(255),
    fine_authority      VARCHAR(100),
    legal_basis         VARCHAR(255),
    explanation         TEXT,
    recommendation      TEXT,
    confidence          DOUBLE PRECISION,
    verification_status VARCHAR(30),
    evidence_type       VARCHAR(30),
    matched_signals     TEXT,
    page_url            TEXT,
    created_at          TIMESTAMP    NOT NULL
);

CREATE INDEX idx_compliance_findings_scan_created  ON compliance_findings(scan_id, created_at ASC);
CREATE INDEX idx_compliance_findings_scan_severity ON compliance_findings(scan_id, severity);
CREATE INDEX idx_compliance_findings_scan_category ON compliance_findings(scan_id, category);
CREATE INDEX idx_compliance_findings_code          ON compliance_findings(code);

CREATE TABLE scan_emails (
    id                    UUID         PRIMARY KEY,
    scan_id               UUID         NOT NULL REFERENCES compliance_scans(id) ON DELETE CASCADE,
    email                 VARCHAR(255) NOT NULL,
    consent_to_processing BOOLEAN      NOT NULL,
    consent_to_marketing  BOOLEAN      NOT NULL,
    consent_ip            VARCHAR(45)  NOT NULL,
    consent_at            TIMESTAMP    NOT NULL
);

CREATE INDEX idx_scan_emails_scan ON scan_emails(scan_id);
