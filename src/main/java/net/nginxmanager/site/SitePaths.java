package net.nginxmanager.site;

/** 사이트 설정 파일 위치. 설정 루트 기준 상대 경로. */
public final class SitePaths {

    public static final String ENABLED_DIR = "conf.d/sites/";
    public static final String DISABLED_DIR = "sites-disabled/";

    private SitePaths() {
    }

    public static String of(String primaryDomain, boolean enabled) {
        return (enabled ? ENABLED_DIR : DISABLED_DIR) + primaryDomain + ".conf";
    }

    /** 사이트 디렉터리 안의 파일이면 도메인을, 아니면 null을 돌려준다. */
    public static String domainOf(String path) {
        for (String dir : new String[] {ENABLED_DIR, DISABLED_DIR}) {
            // _로 시작하는 파일은 관리도구 자체의 설정(관리 화면 프록시)이다. 도메인은 _로 시작할 수 없다
            if (path.startsWith(dir) && path.endsWith(".conf") && path.indexOf('/', dir.length()) < 0
                    && !path.startsWith("_", dir.length())) {
                return path.substring(dir.length(), path.length() - ".conf".length());
            }
        }
        return null;
    }
}
