package net.nginxmanager.config;

public enum VersionResult {
    SUCCESS("적용됨"),
    VALIDATION_FAILED("nginx -t 실패"),
    AUTO_RECOVERED("자동 롤백");

    private final String label;

    VersionResult(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
