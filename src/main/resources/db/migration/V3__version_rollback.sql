-- 되돌리기로 만든 버전이 어느 버전을 대상으로 했는지 남긴다.
ALTER TABLE config_version ADD COLUMN rolled_back_to_id BIGINT REFERENCES config_version (id);
