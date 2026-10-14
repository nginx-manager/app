-- 마지막으로 nginx에 반영된 폼 값(초안을 버릴 때와 인증서 갱신 때 쓴다). 기존 사이트는 비워 두면 지금 값을 쓴다.
ALTER TABLE site ADD COLUMN applied_settings TEXT;
