package net.nginxmanager.site;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 추가 지시어(032) 가운데 담당 멤버가 넣을 수 있는 것과 관리자만 넣는 것을 가른다(035).
 * 서버의 파일을 읽거나 쓰는 지시어, 다른 사이트의 도메인·포트를 가로챌 수 있는 지시어, 코드를 돌리는 지시어는
 * 그 사이트 하나가 아니라 서버 전체 운영에 영향을 주므로 관리자만 넣는다. 멤버는 관리자에게 요청한다.
 */
public final class ServerDirectivePolicy {

    /** 지시어를 읽을 수 없을 때 돌려주는 표시. 이런 값은 관리자만 넣는다. */
    public static final String UNREADABLE = "?";

    private static final Set<String> ADMIN_ONLY = Set.of(
            "root", "alias", "include", "load_module", "access_log", "error_log", "listen", "server_name",
            "proxy_store", "fastcgi_store", "uwsgi_store", "scgi_store", "user", "pid", "env", "daemon",
            "master_process", "working_directory", "lock_file", "ssl_engine");

    private static final List<String> ADMIN_ONLY_SUFFIXES = List.of(
            "_path", "_file", "_certificate", "_certificate_key", "_crl", "_dhparam", "_ticket_key");

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
            new Example("healthz", "location = /healthz { return 200 'ok'; }"),
            new Example("denyPath", "location ^~ /internal/ { deny all; }"));

    /** 도움말에 보이는 관리자 전용 지시어(접미사·접두사 규칙은 문구로 설명한다). */
    public static final List<String> ADMIN_ONLY_SHOWN = List.of(
            "root", "alias", "include", "listen", "server_name", "access_log", "error_log", "ssl_certificate");

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

    private static void collect(List<NginxConf.Directive> directives, Set<String> found) {
        for (NginxConf.Directive d : directives) {
            if (isAdminOnly(d.name())) {
                found.add(d.args().isEmpty() ? d.name() : d.name() + " " + String.join(" ", d.args()));
            }
            if (d.isBlock()) {
                collect(d.block(), found);
            }
        }
    }
}
