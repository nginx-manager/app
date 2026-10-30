-- 채널마다 받을 알림 종류(028). 쉼표로 나눈 NotificationEvent 이름, NULL이면 모두 받는다
ALTER TABLE notification_channel ADD COLUMN events VARCHAR(200);
