CREATE TABLE certificate (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    name                  VARCHAR(253) NOT NULL UNIQUE,
    domains               TEXT         NOT NULL,
    source                VARCHAR(20)  NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    cert_path             VARCHAR(500),
    key_path              VARCHAR(500),
    not_before            TIMESTAMP,
    not_after             TIMESTAMP,
    auto_renew            BOOLEAN      NOT NULL DEFAULT 0,
    next_renewal_at       TIMESTAMP,
    last_attempt_at       TIMESTAMP,
    last_error            TEXT,
    failure_count         INTEGER      NOT NULL DEFAULT 0,
    retry_after           TIMESTAMP,
    last_expiry_notice_at TIMESTAMP,
    created_at            TIMESTAMP    NOT NULL,
    updated_at            TIMESTAMP    NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0
);

ALTER TABLE site ADD COLUMN https_enabled BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE site ADD COLUMN redirect_http_to_https BOOLEAN NOT NULL DEFAULT 1;
ALTER TABLE site ADD COLUMN certificate_id BIGINT REFERENCES certificate (id);

CREATE TABLE acme_settings (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    environment   VARCHAR(20)  NOT NULL,
    contact_email VARCHAR(254),
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE notification_channel (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    type       VARCHAR(20)  NOT NULL,
    target     VARCHAR(500) NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT 1,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0
);
