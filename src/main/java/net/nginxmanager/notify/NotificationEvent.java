package net.nginxmanager.notify;

/** 채널이 골라 받는 알림 종류(028). 알림 하나의 event 값은 종류보다 자세하다(CERT_EXPIRING, NGINX_DOWN …). */
public enum NotificationEvent {
    CERT,
    APPLY_FAILED,
    NGINX_RESTART,
    DRIFT,
    BACKUP_FAILED,
    ACCOUNT_LOCKED;

    /** 알림의 event 값이 속한 종류. 어느 종류도 아니면(TEST 등) null이고, 그런 알림은 거르지 않는다. */
    static NotificationEvent of(String event) {
        if (event == null) {
            return null;
        }
        if (event.startsWith("CERT_")) {
            return CERT;
        }
        if (event.startsWith("NGINX_")) {
            return NGINX_RESTART;
        }
        if (event.startsWith("DRIFT_")) {
            return DRIFT;
        }
        for (NotificationEvent kind : values()) {
            if (kind.name().equals(event)) {
                return kind;
            }
        }
        return null;
    }
}
