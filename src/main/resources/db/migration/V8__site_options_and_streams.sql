-- 사이트: 리디렉션 유형, 모듈 옵션(Properties 형식), 비밀번호 보호 사용자의 htpasswd 줄(이름:해시)
ALTER TABLE site ADD COLUMN redirect_target VARCHAR(2048);
ALTER TABLE site ADD COLUMN redirect_code INTEGER;
ALTER TABLE site ADD COLUMN redirect_keep_path BOOLEAN NOT NULL DEFAULT 1;
ALTER TABLE site ADD COLUMN options TEXT;
ALTER TABLE site ADD COLUMN auth_htpasswd TEXT;

-- TCP/UDP 스트림 프록시. 설정 파일은 stream.d/<프로토콜>-<포트>.conf 하나이고, 경로로 찾는다
-- (삭제를 적용하면 설정 파일 행이 먼저 사라지므로 외래 키로 묶지 않는다)
CREATE TABLE stream_proxy (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    protocol        VARCHAR(3)   NOT NULL,
    listen_port     INTEGER      NOT NULL,
    backends        TEXT         NOT NULL,
    load_balancing  VARCHAR(20)  NOT NULL DEFAULT 'ROUND_ROBIN',
    connect_timeout INTEGER,
    idle_timeout    INTEGER,
    note            VARCHAR(200),
    raw             BOOLEAN      NOT NULL DEFAULT 0,
    -- 마지막으로 nginx에 반영된 폼 값. 초안을 버리면 이 값으로 되돌린다
    applied_settings TEXT,
    config_path     VARCHAR(255) NOT NULL UNIQUE,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (protocol, listen_port)
);
