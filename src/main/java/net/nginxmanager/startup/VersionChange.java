package net.nginxmanager.startup;

import java.time.Instant;

/** 지난번 시작과 이번 시작 사이에 바뀐 버전. */
public record VersionChange(String previousNginx, String currentNginx, String previousApp, String currentApp,
                            Instant at) {

    public boolean nginxChanged() {
        return !java.util.Objects.equals(previousNginx, currentNginx);
    }

    public boolean appChanged() {
        return !java.util.Objects.equals(previousApp, currentApp);
    }

    public String describe() {
        StringBuilder out = new StringBuilder();
        if (nginxChanged()) {
            out.append("nginx ").append(previousNginx).append(" → ").append(currentNginx);
        }
        if (appChanged()) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append("관리도구 ").append(previousApp).append(" → ").append(currentApp);
        }
        return out.toString();
    }
}
