package net.nginxmanager.site;

import java.util.Locale;

/**
 * nginx는 컨테이너 안에서 돈다. 그 안의 127.0.0.1·localhost는 서버(호스트)가 아니라 nginx 컨테이너 자신이라
 * 같은 서버의 앱으로 프록시하려고 127.0.0.1을 적으면 502가 난다. 컨테이너에서 호스트를 부르는 이름은
 * {@link #HOST}이고, compose가 nginx 서비스에 extra_hosts(host-gateway)로 넣어 둔다.
 */
public final class Loopback {

    /** nginx 컨테이너에서 호스트(서버)를 부르는 이름. */
    public static final String HOST = "host.docker.internal";

    private Loopback() {
    }

    /** 컨테이너 자신을 가리키는 주소인지: localhost, 127.0.0.0/8, 0.0.0.0, ::1. */
    public static boolean is(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.equals("0.0.0.0") || h.equals("[::1]")
                || h.equals("::1");
    }

    /**
     * "스킴://호스트:포트/경로"나 "호스트:포트"의 루프백 호스트를 {@link #HOST}로 바꾼다. 아니면 그대로 돌려준다.
     * 호스트의 nginx 설정을 가져올 때 쓴다: 호스트에서 127.0.0.1은 같은 서버의 앱이었다.
     */
    public static String toHost(String target) {
        if (target == null) {
            return null;
        }
        int schemeEnd = target.indexOf("://");
        String prefix = schemeEnd < 0 ? "" : target.substring(0, schemeEnd + 3);
        String rest = schemeEnd < 0 ? target : target.substring(schemeEnd + 3);
        if (rest.startsWith("unix:")) {
            return target;
        }
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String tail = slash < 0 ? "" : rest.substring(slash);
        String host;
        String port;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            host = close < 0 ? authority : authority.substring(0, close + 1);
            port = close < 0 ? "" : authority.substring(close + 1);
        } else {
            int colon = authority.lastIndexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
            port = colon < 0 ? "" : authority.substring(colon);
        }
        return is(host) ? prefix + HOST + port + tail : target;
    }

    /** 미리보기 맨 위에 경고를 nginx 주석으로 붙인다. 미리보기는 설정 원문이라 주석이면 그대로 읽힌다. */
    public static String warn(String preview, boolean loopback, String warning) {
        if (!loopback || preview == null) {
            return preview;
        }
        return "# " + warning.replace("\n", "\n# ") + "\n\n" + preview;
    }

    /** 대상 주소의 호스트가 루프백인지. 형식이 틀린 값은 false(형식 검사가 따로 잡는다). */
    public static boolean target(String target) {
        return target != null && !toHost(target).equals(target);
    }
}
