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
            if (path.startsWith(dir) && path.endsWith(".conf") && path.indexOf('/', dir.length()) < 0) {
                return path.substring(dir.length(), path.length() - ".conf".length());
            }
        }
        return null;
    }
}
