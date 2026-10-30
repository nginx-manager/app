package net.nginxmanager.site;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 경로별 라우팅 한 줄(019): 이 경로 아래 요청을 다른 백엔드(proxy_pass)나 디렉터리(alias)로 보낸다.
 * 대상이 http(s) 주소면 프록시, /로 시작하면 디렉터리다.
 *
 * @param path   location 경로. /로 시작한다
 * @param target 프록시 주소나 디렉터리
 */
public record PathRoute(String path, String target) {

    static final int MAX_ROUTES = 50;

    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9._~/-]*");
    private static final Pattern PROXY = Pattern.compile("https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?");
    private static final Pattern DIRECTORY = Pattern.compile("/[A-Za-z0-9._/-]*");

    public boolean proxy() {
        return target.startsWith("http://") || target.startsWith("https://");
    }

    /** alias는 location 경로를 떼고 디렉터리 뒤에 붙이므로, 둘 다 /로 끝나야 경로가 붙어 버리지 않는다. */
    String locationPath() {
        return proxy() || path.endsWith("/") ? path : path + "/";
    }

    String aliasDirectory() {
        return target.endsWith("/") ? target : target + "/";
    }

    /** 줄마다 "경로 대상". 빈 줄은 건너뛰고, 칸이 둘이 아닌 줄은 그대로 남겨 검증에서 걸리게 한다. */
    static List<PathRoute> parse(String value) {
        List<PathRoute> routes = new ArrayList<>();
        if (value == null) {
            return routes;
        }
        for (String line : value.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            routes.add(parts.length == 2 ? new PathRoute(parts[0], parts[1]) : new PathRoute(trimmed, ""));
        }
        return routes;
    }

    static String normalized(String value) {
        List<PathRoute> routes = parse(value);
        return routes.isEmpty() ? null
                : String.join("\n", routes.stream().map(r -> r.target().isEmpty() ? r.path() : r.path() + " " + r.target())
                .toList());
    }

    static boolean valid(String value) {
        List<PathRoute> routes = parse(value);
        if (routes.size() > MAX_ROUTES) {
            return false;
        }
        Set<String> paths = new HashSet<>();
        for (PathRoute route : routes) {
            if (!route.isValid() || !paths.add(route.locationPath())) {
                return false;
            }
        }
        return true;
    }

    private boolean isValid() {
        if (!PATH.matcher(path).matches() || path.equals("/") || path.contains("..") || path.contains("//")) {
            return false;
        }
        if (proxy()) {
            if (!PROXY.matcher(target).matches()) {
                return false;
            }
            ProxyBackend backend = ProxyBackend.parse(target);
            return backend.port() >= 1 && backend.port() <= 65535 && !backend.isManager();
        }
        if (!DIRECTORY.matcher(target).matches() || target.contains("..")) {
            return false;
        }
        String directory = target.replaceAll("/+$", "");
        return !directory.isEmpty() && SiteForm.PROTECTED_DIRS.stream().noneMatch(dir -> directory.equals(dir)
                || directory.startsWith(dir + "/") || dir.startsWith(directory + "/"));
    }
}
