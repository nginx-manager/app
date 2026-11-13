-- 담당 멤버가 관리자에게 보낸 추가 지시어 요청(035). 사이트마다 검토할 요청은 하나다(새 요청이 앞 요청을 바꾼다)
CREATE TABLE directive_request (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    site_id      BIGINT       NOT NULL UNIQUE REFERENCES site (id) ON DELETE CASCADE,
    requested_by VARCHAR(50)  NOT NULL,
    directives   TEXT         NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0
);
