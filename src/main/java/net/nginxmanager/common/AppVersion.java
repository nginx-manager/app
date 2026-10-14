package net.nginxmanager.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/** 관리도구 릴리스 버전. 빌드 정보 없이 실행하면 dev. */
@Component
public class AppVersion {

    private final ObjectProvider<BuildProperties> buildProperties;

    public AppVersion(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    public String current() {
        BuildProperties build = buildProperties.getIfAvailable();
        return build == null ? "dev" : build.getVersion();
    }

    /** 개발 빌드(dev, -SNAPSHOT)는 다른 버전과 비교하지 않는다. */
    public static boolean isDevelopment(String version) {
        return version == null || version.isBlank() || version.equals("dev") || version.endsWith("-SNAPSHOT");
    }

    /** 두 릴리스 버전이 다른지. 어느 한쪽이 개발 빌드면 다르다고 보지 않는다. */
    public static boolean differ(String a, String b) {
        if (isDevelopment(a) || isDevelopment(b)) {
            return false;
        }
        return !strip(a).equals(strip(b));
    }

    private static String strip(String version) {
        return version.startsWith("v") ? version.substring(1) : version;
    }
}
