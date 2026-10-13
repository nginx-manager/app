package net.nginxmanager.config;

public enum VersionSource {
    INITIAL_IMPORT("처음 가져오기"),
    APPLY("적용"),
    ROLLBACK("되돌리기"),
    DRIFT_IMPORT("외부 변경 가져오기"),
    CERT_ISSUE("인증서 발급"),
    CERT_RENEW("인증서 갱신");

    private final String label;

    VersionSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
