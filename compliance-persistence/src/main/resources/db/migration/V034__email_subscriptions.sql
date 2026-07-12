CREATE TABLE email_subscriptions (
    id                 UUID         PRIMARY KEY,
    user_id            BIGINT       REFERENCES app_users(id) ON DELETE SET NULL,
    email              VARCHAR(255) NOT NULL,
    normalized_email   VARCHAR(255) NOT NULL UNIQUE,
    locale             VARCHAR(10)  NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    source             VARCHAR(40)  NOT NULL,
    consent_at         TIMESTAMP    NOT NULL,
    consent_ip         VARCHAR(45),
    unsubscribed_at    TIMESTAMP,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    CONSTRAINT chk_email_subscription_status
        CHECK (status IN ('SUBSCRIBED', 'UNSUBSCRIBED'))
);

CREATE INDEX idx_email_subscriptions_status
    ON email_subscriptions(status, created_at);
