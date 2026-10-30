-- 로그인 2단계 인증(017). 비밀 키는 켜기 전에도 저장해 두고(QR을 다시 보여 줄 수 있게), 켜져야 쓴다
ALTER TABLE users ADD COLUMN totp_secret VARCHAR(64);
ALTER TABLE users ADD COLUMN totp_enabled BOOLEAN NOT NULL DEFAULT 0;
-- 마지막으로 받은 코드의 시간 칸. 같은 코드를 다시 쓰지 못하게 한다
ALTER TABLE users ADD COLUMN totp_last_step BIGINT;
-- 복구 코드의 BCrypt 해시를 줄마다 하나씩. 쓴 코드는 지운다
ALTER TABLE users ADD COLUMN recovery_codes TEXT;
