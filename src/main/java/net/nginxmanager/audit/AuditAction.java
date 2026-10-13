package net.nginxmanager.audit;

public enum AuditAction {
    LOGIN,
    ACCOUNT_LOCKED,
    USER_CREATE,
    USER_CHANGE,
    SITE_SAVE,
    APPLY,
    ROLLBACK,
    DRIFT_IMPORT,
    CERT_ISSUE,
    CERT_RENEW,
    CERT_UPLOAD,
    RUNTIME_START,
    UPGRADE,
    NGINX_RESTART
}
