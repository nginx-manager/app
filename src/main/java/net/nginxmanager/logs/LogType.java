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

    /** nginx 지시어 이름(access_log, error_log). */
    public String directive() {
        return suffix + "_log";
    }
}
