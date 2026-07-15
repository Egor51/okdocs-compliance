CREATE TABLE report_remediation_requests (
    id                       UUID          PRIMARY KEY,
    scan_id                  UUID          NOT NULL REFERENCES compliance_scans(id) ON DELETE CASCADE,
    user_id                  BIGINT        NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    site_url_snapshot        VARCHAR(2048) NOT NULL,
    customer_email_snapshot  VARCHAR(255)  NOT NULL,
    status                   VARCHAR(30)   NOT NULL,
    locale                   VARCHAR(16)   NOT NULL,
    created_at               TIMESTAMP     NOT NULL,
    updated_at               TIMESTAMP     NOT NULL,
    CONSTRAINT uq_report_remediation_scan_user UNIQUE (scan_id, user_id),
    CONSTRAINT chk_report_remediation_status CHECK (
        status IN ('NEW', 'CONTACTED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')
    )
);

CREATE INDEX idx_report_remediation_status_created
    ON report_remediation_requests(status, created_at);
