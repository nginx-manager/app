CREATE TABLE config_file (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    path             VARCHAR(255) NOT NULL UNIQUE,
    draft_content    TEXT,
    draft_delete     BOOLEAN      NOT NULL DEFAULT 0,
    last_known_hash  VARCHAR(64),
    managed          BOOLEAN      NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE site (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    primary_domain   VARCHAR(253) NOT NULL UNIQUE,
    extra_domains    TEXT,
    type             VARCHAR(10)  NOT NULL,
    listen_http_port INTEGER      NOT NULL DEFAULT 80,
    static_root      VARCHAR(255),
    proxy_target     VARCHAR(255),
    enabled          BOOLEAN      NOT NULL DEFAULT 1,
    config_file_id   BIGINT       NOT NULL REFERENCES config_file (id),
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE config_version (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    author_id         BIGINT REFERENCES users (id),
    author_name       VARCHAR(50),
    message           VARCHAR(500),
    source            VARCHAR(20)  NOT NULL,
    result            VARCHAR(20)  NOT NULL,
    validation_output TEXT,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE config_version_file (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    config_version_id BIGINT       NOT NULL REFERENCES config_version (id),
    path              VARCHAR(255) NOT NULL,
    content           TEXT,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (config_version_id, path)
);
