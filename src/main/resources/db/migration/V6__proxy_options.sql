-- 리버스 프록시 폼: 웹소켓, 타임아웃, 백엔드 여러 개(proxy_target에 줄마다 하나)
ALTER TABLE site ADD COLUMN proxy_websocket BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE site ADD COLUMN proxy_connect_timeout INTEGER;
ALTER TABLE site ADD COLUMN proxy_read_timeout INTEGER;
ALTER TABLE site ADD COLUMN proxy_load_balancing VARCHAR(20) NOT NULL DEFAULT 'ROUND_ROBIN';
