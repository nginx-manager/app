package net.nginxmanager.site;

import java.util.Locale;

/**
 * 프록시 대상 주소 하나를 upstream의 server 항목과 proxy_pass용 스킴·경로로 나눈다.
 * 형식 검사는 {@link SiteForm}이 먼저 끝낸다.
 *
 * @param path 경로가 없으면 빈 문자열
 */
record ProxyBackend(String scheme, String host, int port, String path) {

    static ProxyBackend parse(String target) {
        int schemeEnd = target.indexOf("://");
        String scheme = target.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        String rest = target.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash);
        int colon = authority.indexOf(':');
        String host = colon < 0 ? authority : authority.substring(0, colon);
        int port = colon < 0 ? ("https".equals(scheme) ? 443 : 80) : Integer.parseInt(authority.substring(colon + 1));
        return new ProxyBackend(scheme, host, port, path);
    }

    /**
     * 관리용 포트를 가리키는지: nginx 컨테이너의 에이전트(7070)·상태 확인(8081), 관리 화면 컨테이너(manager:9000).
     * 관리 화면을 nginx 사이트로 공개하려면 HTTPS 프록시를 따로 둔다.
     */
    boolean isManager() {
        boolean local = host.equals("localhost") || host.startsWith("127.") || host.equals("0.0.0.0");
        boolean agent = local || host.equals("nginx");
        return (agent && (port == 7070 || port == 8081)) || (local && port == 9000)
                || (host.equals("manager") && port == 9000);
    }

    /** upstream의 server 항목. 포트는 늘 적는다(https의 기본 포트는 80이 아니라서). */
    String server() {
        return host + ":" + port;
    }
}
