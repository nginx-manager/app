package net.nginxmanager.logs;

public enum LogType {
    ACCESS("access"),
    ERROR("error");

    private final String suffix;

    LogType(String suffix) {
        this.suffix = suffix;
    }

    public String suffix() {
        return suffix;
    }
}
