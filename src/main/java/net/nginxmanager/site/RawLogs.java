package net.nginxmanager.site;

import java.util.ArrayList;
import java.util.List;

/**
 * 원문 사이트의 로그 위치(server 블록 바로 아래의 access_log/error_log)를 읽고, 사이트별 로그 파일로 남기는 두 줄을 더한다.
 * 원문은 다시 쓰지 않고 server 블록의 여는 괄호 뒤에 글자 그대로 더한다. 원래 있던 로그 지시어는 지우지 않는다
 * (nginx는 같은 단계의 여러 access_log에 모두 쓴다).
 */
public final class RawLogs {

    /** nginx 설정에서 로그 루트. 폼 사이트 설정(SiteConfigRenderer)도 이 경로를 쓴다. */
    public static final String NGINX_LOG_DIR = "/var/log/nginx/";
    private static final String COMMENT = "# 관리도구 로그 화면에서 볼 수 있게 사이트별 로그로 남긴다";

    public enum Status {
        /** 로그 루트 안의 파일. */
        FILE,
        /** 지시어가 없다. 공통 로그(http 단계)에 쌓인다. */
        NOT_SET,
        /** access_log off. */
        OFF,
        /** 변수, syslog:, memory:, 상대 경로처럼 파일 하나로 읽을 수 없다. */
        UNSUPPORTED,
        /** 로그 루트 밖의 파일. */
        OUTSIDE
    }

    /** @param path 설정에 적힌 경로(FILE, OUTSIDE일 때) */
    public record Found(Status status, String path) {
    }

    private RawLogs() {
    }

    /** 폼 사이트와 같은 사이트별 로그 경로. */
    public static String sitePath(String primaryDomain, String suffix) {
        return NGINX_LOG_DIR + "sites/" + primaryDomain + "." + suffix + ".log";
    }

    /**
     * server 블록 바로 아래의 directive(access_log, error_log)를 찾는다. 읽을 수 있는 파일이 하나라도 있으면 그 첫 파일,
     * 없으면 처음 만난 지시어의 상태를 돌려준다. location 안의 로그는 사이트 전체 로그가 아니므로 보지 않는다.
     */
    public static Found find(String content, String directive) {
        List<NginxConf.Directive> servers;
        try {
            servers = servers(NginxConf.parse(content));
        } catch (IllegalArgumentException e) {
            return new Found(Status.UNSUPPORTED, null);
        }
        Found first = null;
        for (NginxConf.Directive server : servers) {
            for (NginxConf.Directive d : server.block()) {
                if (!d.name().equals(directive) || d.isBlock()) {
                    continue;
                }
                Found found = classify(d.arg(0));
                if (found.status() == Status.FILE) {
                    return found;
                }
                if (first == null) {
                    first = found;
                }
            }
        }
        return first != null ? first : new Found(Status.NOT_SET, null);
    }

    /**
     * 사이트별 로그 경로를 가리키는 지시어가 없는 server 블록마다 맨 앞에 access_log/error_log를 더한다.
     * access_log off인 블록에는 access_log를 더하지 않는다. 더할 것이 없으면 그대로 돌려준다.
     */
    public static String withSiteLogs(String content, String primaryDomain) {
        String access = sitePath(primaryDomain, "access");
        String error = sitePath(primaryDomain, "error");
        StringBuilder out = new StringBuilder(content);
        List<RawHttps.Block> blocks = RawHttps.serverBlocks(content);
        for (int i = blocks.size() - 1; i >= 0; i--) {
            RawHttps.Block block = blocks.get(i);
            List<NginxConf.Directive> inner;
            try {
                inner = NginxConf.parse(block.text()).getFirst().block();
            } catch (IllegalArgumentException | java.util.NoSuchElementException e) {
                continue;
            }
            boolean addAccess = !has(inner, "access_log", access) && !has(inner, "access_log", "off");
            boolean addError = !has(inner, "error_log", error);
            if (!addAccess && !addError) {
                continue;
            }
            String ind = RawHttps.indent(block.text());
            StringBuilder lines = new StringBuilder("\n").append(ind).append(COMMENT).append("\n");
            if (addAccess) {
                lines.append(ind).append("access_log ").append(access).append(" main;\n");
            }
            if (addError) {
                lines.append(ind).append("error_log ").append(error).append(";\n");
            }
            out.insert(block.open(), lines);
        }
        return out.toString();
    }

    private static boolean has(List<NginxConf.Directive> directives, String name, String firstArg) {
        return directives.stream().anyMatch(d -> d.name().equals(name) && firstArg.equals(d.arg(0)));
    }

    private static Found classify(String path) {
        if (path == null) {
            return new Found(Status.UNSUPPORTED, null);
        }
        if (path.equals("off")) {
            return new Found(Status.OFF, null);
        }
        // 변수, syslog:, memory:, stderr, 상대 경로
        if (path.contains("$") || !path.startsWith("/")) {
            return new Found(Status.UNSUPPORTED, path);
        }
        return new Found(path.startsWith(NGINX_LOG_DIR) ? Status.FILE : Status.OUTSIDE, path);
    }

    /** 맨 바깥이나 http 블록 바로 안의 server 블록. */
    private static List<NginxConf.Directive> servers(List<NginxConf.Directive> directives) {
        List<NginxConf.Directive> servers = new ArrayList<>();
        for (NginxConf.Directive d : directives) {
            if (!d.isBlock()) {
                continue;
            }
            if (d.name().equals("server")) {
                servers.add(d);
            } else if (d.name().equals("http")) {
                servers.addAll(servers(d.block()));
            }
        }
        return servers;
    }
}
