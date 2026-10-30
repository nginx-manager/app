package net.nginxmanager.config;

public enum VersionSource {
    INITIAL_IMPORT("초기 가져오기"),
    APPLY("적용"),
    ROLLBACK("롤백"),
    DRIFT_IMPORT("드리프트 가져오기"),
    CERT_ISSUE("인증서 발급"),
    CERT_RENEW("인증서 갱신"),
    ADMIN_DOMAIN("관리 화면 도메인");

    private final String label;

    VersionSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
