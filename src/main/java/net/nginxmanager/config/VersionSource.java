package net.nginxmanager.config;

public enum VersionSource {
    INITIAL_IMPORT,
    APPLY,
    ROLLBACK,
    DRIFT_IMPORT,
    CERT_ISSUE,
    CERT_RENEW
}
