package net.nginxmanager.config;

public enum VersionResult {
    SUCCESS("적용됨"),
    VALIDATION_FAILED("검증 실패"),
    AUTO_RECOVERED("자동 복구");

    private final String label;

    VersionResult(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
