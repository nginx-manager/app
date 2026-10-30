-- 배포 자동화용 API 토큰(033). 값은 SHA-256 해시로만 남기고, 화면에는 앞 몇 글자만 보여 준다
CREATE TABLE api_token (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         VARCHAR(50)  NOT NULL UNIQUE,
    token_hash   VARCHAR(64)  NOT NULL UNIQUE,
    token_hint   VARCHAR(20)  NOT NULL,
    created_by   VARCHAR(50),
    last_used_at TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0
);
