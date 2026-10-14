package net.nginxmanager.runtime;

public record HealthResult(boolean healthy, String detail) {

    public static HealthResult ok() {
        return new HealthResult(true, "ok");
    }

    public static HealthResult failed(String detail) {
        return new HealthResult(false, detail);
    }
}
