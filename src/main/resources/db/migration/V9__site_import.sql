-- 설정 파일 가져오기(spec 005).
-- 등록해 둔 가져오기 위치(경로 패턴). 다시 검사하면 새로 생기거나 바뀐 파일을 보여 준다
CREATE TABLE import_source (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    pattern     VARCHAR(1024) NOT NULL UNIQUE,
    created_by  VARCHAR(100),
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0
);

-- 사이트를 가져온 원본 파일(절대 경로)과 그때의 내용 해시. 원본이 바뀌었는지 알려 주는 데 쓴다
ALTER TABLE site ADD COLUMN import_path VARCHAR(1024);
ALTER TABLE site ADD COLUMN import_hash VARCHAR(64);

-- 가져오기로 만든 사이트 파일 초안이 대신하는(함께 지우는) 설정 루트 안의 원본 경로. 두 초안을 함께 버리는 데 쓴다
ALTER TABLE config_file ADD COLUMN imported_from VARCHAR(1024);
