package net.nginxmanager.site;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 추가 지시어(032) 가운데 담당 멤버가 넣을 수 있는 것과 관리자만 넣는 것을 가른다(035).
 * 담당자는 자기 사이트를 등록하고 운영하는 데 필요한 지시어(root, alias, listen, server_name, ssl_certificate, location,
 * 헤더, 타임아웃, 버퍼 등)를 넣는다. 서버에 파일을 만들거나 쓰는 지시어(로그, 임시·캐시 경로, *_store), 다른 설정이나
 * 코드를 불러오는 지시어(include, load_module, perl·js·lua), 메인 컨텍스트 지시어만 관리자가 넣고, 멤버는 요청한다.
 * listen·server_name이 다른 사이트의 도메인이나 스트림 포트와 겹치는지는 저장할 때 {@link SiteService}가 본다.
 */
public final class ServerDirectivePolicy {

    /** 지시어를 읽을 수 없을 때 돌려주는 표시. 이런 값은 관리자만 넣는다. */
    public static final String UNREADABLE = "?";

    private static final Set<String> ADMIN_ONLY = Set.of(
            "include", "load_module", "access_log", "error_log",
            "proxy_store", "fastcgi_store", "uwsgi_store", "scgi_store", "user", "pid", "env", "daemon",
            "master_process", "working_directory", "lock_file", "ssl_engine");

    private static final List<String> ADMIN_ONLY_SUFFIXES = List.of(
            "_temp_path", "_cache_path");

    private static final List<String> ADMIN_ONLY_PREFIXES = List.of("perl", "js_", "lua", "njs", "wasm", "worker_");

    /** 추가 지시어 칸 아래 도움말에 보이는 자주 쓰는 지시어. key는 설명 문구 키 끝부분이다. */
    public record Example(String key, String code) {
    }

    public static final List<Example> EXAMPLES = List.of(
            new Example("bodyTimeout", "client_body_timeout 60s;"),
            new Example("keepalive", "keepalive_timeout 30s;"),
            new Example("requestBuffering", "proxy_request_buffering off;"),
            new Example("buffering", "proxy_buffering off;"),
            new Example("httpVersion", "proxy_http_version 1.1;"),
            new Example("readTimeout", "proxy_read_timeout 300s;"),
            new Example("bufferSize", "proxy_buffer_size 16k;"),
            new Example("headerBuffers", "large_client_header_buffers 4 16k;"),
            new Example("serverTokens", "server_tokens off;"),
            new Example("charset", "charset utf-8;"),
            new Example("rewrite", "rewrite ^/old/(.*)$ /new/$1 permanent;"),
            new Example("alias", "location /files/ { alias /srv/files/; }"),
            new Example("addHeader", "add_header X-Frame-Options SAMEORIGIN always;"),
            new Example("errorPage", "error_page 404 /404.html;"),
            new Example("healthz", "location = /healthz { return 200 'ok'; }"),
            new Example("denyPath", "location ^~ /internal/ { deny all; }"));

    /** 도움말에 보이는 관리자 전용 지시어(접미사·접두사 규칙은 문구로 설명한다). */
    public static final List<String> ADMIN_ONLY_SHOWN = List.of(
            "include", "access_log", "error_log", "proxy_temp_path", "js_content");

    private ServerDirectivePolicy() {
    }

    public static boolean isAdminOnly(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return ADMIN_ONLY.contains(n)
                || ADMIN_ONLY_SUFFIXES.stream().anyMatch(n::endsWith)
                || ADMIN_ONLY_PREFIXES.stream().anyMatch(n::startsWith);
    }

    /**
     * 관리자만 넣는 지시어를 "이름 인자…" 꼴로 찾는다(location·if 블록 안까지). 읽을 수 없으면 {@link #UNREADABLE} 하나.
     */
    public static List<String> adminOnly(String directives) {
        if (directives == null || directives.isBlank()) {
            return List.of();
        }
        List<NginxConf.Directive> parsed;
        try {
            parsed = NginxConf.parse(directives);
        } catch (IllegalArgumentException e) {
            return List.of(UNREADABLE);
        }
        Set<String> found = new LinkedHashSet<>();
        collect(parsed, found);
        return List.copyOf(found);
    }

    /**
     * 지금 값에는 없는데 새 값에 들어온 관리자 전용 지시어. 관리자가 넣어 둔 지시어는 멤버가 저장해도 그대로 둘 수 있다.
     */
    public static List<String> added(String requested, String current) {
        List<String> before = adminOnly(current);
        List<String> result = new ArrayList<>();
        for (String directive : adminOnly(requested)) {
            if (!before.contains(directive)) {
                result.add(directive);
            }
        }
        return result;
    }

    /** 도움말에 이름만 보일 때 쓰는 지시어 이름 목록. */
    public static List<String> names(List<String> directives) {
        Set<String> names = new LinkedHashSet<>();
        for (String directive : directives) {
            int space = directive.indexOf(' ');
            names.add(space < 0 ? directive : directive.substring(0, space));
        }
        return List.copyOf(names);
    }

    /** server 블록에 바로 적힌 server_name의 이름들(035). 다른 사이트의 도메인과 겹치면 저장하지 않는다. */
    public static List<String> serverNames(String directives) {
        List<String> names = new ArrayList<>();
        for (NginxConf.Directive d : topLevel(directives)) {
            if (d.name().equalsIgnoreCase("server_name")) {
                for (String name : d.args()) {
                    if (!name.isEmpty() && !name.equals("_") && !name.startsWith("~") && !name.contains("*")) {
                        names.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return names;
    }

    /** server 블록에 바로 적힌 listen의 TCP 포트들(035). 스트림이 쓰는 포트면 저장하지 않는다. */
    public static List<Integer> listenPorts(String directives) {
        List<Integer> ports = new ArrayList<>();
        for (NginxConf.Directive d : topLevel(directives)) {
            String address = d.name().equalsIgnoreCase("listen") ? d.arg(0) : null;
            if (address == null || address.startsWith("unix:")) {
                continue;
            }
            String port = address.substring(address.lastIndexOf(':') + 1);
            if (port.matches("\\d{1,5}")) {
                ports.add(Integer.parseInt(port));
            }
        }
        return ports;
    }

    private static List<NginxConf.Directive> topLevel(String directives) {
        if (directives == null || directives.isBlank()) {
            return List.of();
        }
        try {
            return NginxConf.parse(directives);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private static boolean adminOnly(NginxConf.Directive d) {
        // 로그 끄기는 파일을 만들지 않는다
        if (d.name().equalsIgnoreCase("access_log") && d.args().size() == 1 && d.args().getFirst().equals("off")) {
            return false;
        }
        if (d.name().equalsIgnoreCase("listen")) {
            String address = d.arg(0);
            return address != null && address.startsWith("unix:");
        }
        return isAdminOnly(d.name());
    }

    private static void collect(List<NginxConf.Directive> directives, Set<String> found) {
        for (NginxConf.Directive d : directives) {
            if (adminOnly(d)) {
                found.add(d.args().isEmpty() ? d.name() : d.name() + " " + String.join(" ", d.args()));
            }
            if (d.isBlock()) {
                collect(d.block(), found);
            }
        }
    }
}
