-- 스트림 접근 제한과 PROXY protocol(025). 주소는 줄마다 하나
ALTER TABLE stream_proxy ADD COLUMN allow_from TEXT;
ALTER TABLE stream_proxy ADD COLUMN deny_from TEXT;
ALTER TABLE stream_proxy ADD COLUMN proxy_protocol BOOLEAN NOT NULL DEFAULT 0;
