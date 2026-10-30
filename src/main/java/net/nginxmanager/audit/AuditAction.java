package net.nginxmanager.audit;

public enum AuditAction {
    LOGIN,
    ACCOUNT_LOCKED,
    USER_CREATE,
    USER_CHANGE,
    SITE_SAVE,
    SITE_IMPORT,
    SITE_ASSIGN,
    SITE_DELETE,
    APPLY,
    ROLLBACK,
    DRIFT_IMPORT,
    CERT_ISSUE,
    CERT_RENEW,
    CERT_UPLOAD,
    CERT_DELETE,
    RUNTIME_START,
    UPGRADE,
    NGINX_RESTART
}
