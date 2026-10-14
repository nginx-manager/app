CREATE TABLE users (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    username           VARCHAR(50)  NOT NULL UNIQUE,
    password_hash      VARCHAR(100) NOT NULL,
    role               VARCHAR(20)  NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT 1,
    failed_login_count INTEGER      NOT NULL DEFAULT 0,
    locked_until       TIMESTAMP,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE audit_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    BIGINT REFERENCES users (id),
    username   VARCHAR(50),
    action     VARCHAR(40)  NOT NULL,
    target     VARCHAR(255),
    result     VARCHAR(20)  NOT NULL,
    detail     TEXT,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_audit_log_created_at ON audit_log (created_at);
CREATE INDEX idx_audit_log_user_id ON audit_log (user_id);
