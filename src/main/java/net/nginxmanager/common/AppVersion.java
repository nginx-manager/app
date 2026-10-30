package net.nginxmanager.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 관리도구 릴리스 버전. git 태그(vX.Y.Z)로 빌드하면 X.Y.Z, 태그 없이 빌드하거나 빌드 정보 없이 실행하면 dev. */
@Component
public class AppVersion {

    static final String RELEASE_NOTES_BASE = "https://github.com/nginx-manager/app/blob/main/docs/releases/";

    private static final Pattern RELEASE = Pattern.compile("v?\\d+\\.\\d+\\.\\d+");

    private final ObjectProvider<BuildProperties> buildProperties;

    public AppVersion(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    public String current() {
        BuildProperties build = buildProperties.getIfAvailable();
        return build == null ? "dev" : build.getVersion();
    }

    /** 화면 footer에 보일 이름. 예: v1.0.0, dev */
    public String label() {
        return label(current());
    }

    /** footer 버전을 누르면 열 릴리스 노트. */
    public String releaseNotesUrl() {
        return releaseNotesUrl(current());
    }

    static String label(String version) {
        return isDevelopment(version) ? "dev" : "v" + strip(version);
    }

    /** 정식 릴리스(1.0.0)는 그 버전의 노트로, 개발 빌드나 시험 태그(1.1.0-rc1)는 릴리스 목록으로 보낸다. */
    static String releaseNotesUrl(String version) {
        return version != null && RELEASE.matcher(version).matches()
                ? RELEASE_NOTES_BASE + "v" + strip(version) + ".md"
                : RELEASE_NOTES_BASE + "README.md";
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
