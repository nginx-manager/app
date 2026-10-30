-- 역할을 관리자/멤버 두 가지로 줄인다(007). 편집자·조회자는 멤버가 된다
UPDATE users SET role = 'MEMBER' WHERE role IN ('EDITOR', 'VIEWER');

-- 사이트 담당자. 비어 있으면 관리자만 본다
ALTER TABLE site ADD COLUMN owner_id BIGINT REFERENCES users (id);
CREATE INDEX idx_site_owner_id ON site (owner_id);
