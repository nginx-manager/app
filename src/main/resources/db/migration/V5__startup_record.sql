-- 시작할 때마다 nginx·관리도구 버전과 시작 검사 결과를 남긴다(FR-025)
CREATE TABLE startup_record (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    nginx_version VARCHAR(50),
    app_version   VARCHAR(50),
    config_ok     BOOLEAN,
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL,
    version       BIGINT    NOT NULL DEFAULT 0
);
