package net.nginxmanager.site;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import net.nginxmanager.site.NginxConf.Directive;
import net.nginxmanager.site.SiteImportAnalysis.Note;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 가져올 설정 파일을 읽어 사이트로 등록할 수 있는지, 폼으로 빠짐없이 옮길 수 있는지 본다.
 * 폼으로 옮길 수 있는 것은 server 블록 하나와 그에 딸린 upstream·웹소켓 map이고, 모든 지시어가 폼 값에 대응할 때뿐이다.
 * 하나라도 모르는 설정이 있으면 원문 사이트로만 등록해 설정이 빠지거나 바뀌지 않게 한다.
 */
@Component
public class SiteImporter {

    /** 사이트 파일에 둘 수 없는 최상위 블록: 전체 설정 파일이다. */
    private static final Set<String> MAIN_BLOCKS = Set.of("events", "http", "stream", "mail");
    /** 폼이 직접 만드는 헤더. 값이 달라도 같은 역할로 본다. */
    private static final Set<String> FORM_HEADERS = Set.of("host", "x-real-ip", "x-forwarded-for", "x-forwarded-proto");
    private static final Pattern DOMAIN = Pattern.compile(SiteForm.DOMAIN);
    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h)?");
    private static final Pattern SIZE = Pattern.compile("(\\d+)([kKmMgG])?");
    /** PHP-FPM으로 넘기는 location의 흔한 모양(016). */
    private static final Set<List<String>> PHP_LOCATIONS = Set.of(List.of("~", "\\.php$"), List.of("~*", "\\.php$"),
            List.of("~", "\\.php(/|$)"), List.of("~", "[^/]\\.php(/|$)"));

    private final Validator validator;

    public SiteImporter(Validator validator) {
        this.validator = validator;
    }

    /**
     * @param primaryDomain 운영자가 고른 대표 도메인. null이면 server_name의 첫 도메인
     */
    public SiteImportAnalysis analyze(String content, String primaryDomain) {
        List<Directive> top;
        try {
            top = NginxConf.parse(content);
        } catch (IllegalArgumentException e) {
            return SiteImportAnalysis.error("import.error.syntax", e.getMessage());
        }
        for (Directive d : top) {
            if (MAIN_BLOCKS.contains(d.name())) {
                return SiteImportAnalysis.error("import.error.mainConfig", d.name());
            }
        }
        List<Directive> servers = top.stream().filter(d -> d.name().equals("server") && d.isBlock()).toList();
        if (servers.isEmpty()) {
            return SiteImportAnalysis.error("import.error.noServer", null);
        }
        Set<String> domains = new LinkedHashSet<>();
        String firstDomain = null;
        for (Directive server : servers) {
            for (Directive d : server.block()) {
                if (d.name().equals("server_name")) {
                    for (String name : d.args()) {
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (lower.startsWith("*.") && DOMAIN.matcher(lower.substring(2)).matches()) {
                            domains.add(lower);
                        } else if (DOMAIN.matcher(lower).matches()) {
                            domains.add(lower);
                            if (firstDomain == null) {
                                firstDomain = lower;
                            }
                        }
                    }
                }
            }
        }
        String primary = primaryDomain == null || primaryDomain.isBlank() ? firstDomain
                : primaryDomain.trim().toLowerCase(Locale.ROOT);
        List<String> ordered = new ArrayList<>();
        if (primary != null) {
            ordered.add(primary);
        }
        domains.stream().filter(d -> !d.equals(primary)).forEach(ordered::add);
        int port = RawListen.httpPort(content).orElse(80);

        Interpretation in = new Interpretation();
        if (servers.size() > 1) {
            in.unsupported("import.reason.multipleServers", String.valueOf(servers.size()));
        }
        Map<String, Directive> upstreams = new LinkedHashMap<>();
        List<Directive> maps = new ArrayList<>();
        for (Directive d : top) {
            switch (d.name()) {
                case "server" -> {
                }
                case "upstream" -> upstreams.put(d.arg(0), d);
                case "map" -> maps.add(d);
                default -> in.unsupported("import.reason.directive", d.name());
            }
        }
        SiteForm form = null;
        if (servers.size() == 1 && primary != null) {
            form = in.toForm(servers.getFirst(), upstreams, maps, primary, ordered);
            if (form != null) {
                for (ConstraintViolation<SiteForm> violation : validator.validate(form)) {
                    in.unsupported("import.reason.invalid", violation.getMessage());
                }
            }
        }
        if (!in.unsupported.isEmpty()) {
            form = null;
        }
        return new SiteImportAnalysis(null, null, servers.size(), List.copyOf(ordered), primary, port, form,
                List.copyOf(in.unsupported), form == null ? List.of() : List.copyOf(in.changes));
    }

    /**
     * 다시 가져올 때 쓸 폼: 원본에서 읽은 값(유형, 도메인, 포트, 내용, gzip·자동 목록·최대 요청 크기)은 새 값으로,
     * 원본 파일에 없는 관리도구 설정(HTTPS, 접근 제한, 비밀번호, 헤더, 속도 제한 등)은 지금 값 그대로 둔다.
     */
    static SiteForm merge(SiteForm current, SiteForm imported) {
        SiteOptions o = current.options();
        SiteOptions n = imported.options();
        SiteOptions options = new SiteOptions(n.gzip(), o.securityHeaders(), o.hsts(), o.http3(), n.maxBodySizeMb(),
                o.allowFrom(), o.denyFrom(), o.authUsers(), o.satisfyAny(), o.rateLimit(), o.rateBurst(),
                o.connLimit(), o.realIpFrom(), o.realIpHeader(),
                imported.type() == SiteType.PROXY ? o.cacheMinutes() : null, n.autoindex(), o.authRequestUrl(),
                o.authSignInUrl(), o.pathRoutes(),
                imported.type() == SiteType.PROXY ? o.backupTargets() : null,
                imported.type() == SiteType.PROXY ? o.upstreamMaxFails() : null,
                imported.type() == SiteType.PROXY ? o.upstreamFailTimeout() : null,
                imported.type() == SiteType.PROXY ? o.upstreamKeepalive() : null, o.maintenance(),
                o.maintenanceMessage(), o.maintenanceAllowFrom(), o.responseHeaders(),
                imported.type() == SiteType.PROXY ? o.requestHeaders() : null, o.redirectToPrimary());
        return new SiteForm(current.primaryDomain(), imported.extraDomains(), imported.type(),
                imported.listenHttpPort(), imported.staticRoot(), imported.proxyTarget(), current.version(),
                current.httpsEnabled(), current.redirectHttpToHttps(), current.certificateId(),
                imported.proxyWebsocket(), imported.proxyConnectTimeout(), imported.proxyReadTimeout(),
                imported.proxyLoadBalancing(), imported.redirectTarget(), imported.redirectCode(),
                imported.redirectKeepPath(), options);
    }

    /** server 블록 하나를 폼 값으로 옮기며 옮길 수 없는 것과 바뀌는 것을 모은다. */
    private static final class Interpretation {

        final List<Note> unsupported = new ArrayList<>();
        final List<Note> changes = new ArrayList<>();

        Integer port;
        String root;
        String proxyPass;
        /** location ~ \\.php$ 의 fastcgi_pass(016). */
        String fastcgiPass;
        Integer redirectCode;
        String redirectTarget;
        boolean websocket;
        Integer connectTimeout;
        Integer readTimeout;
        boolean gzip;
        boolean autoindex;
        Integer maxBodySizeMb;

        void unsupported(String key, String arg) {
            Note note = new Note(key, arg);
            if (!unsupported.contains(note)) {
                unsupported.add(note);
            }
        }

        void change(String key, String arg) {
            Note note = new Note(key, arg);
            if (!changes.contains(note)) {
                changes.add(note);
            }
        }

        SiteForm toForm(Directive server, Map<String, Directive> upstreams, List<Directive> maps, String primary,
                        List<String> domains) {
            Directive rootLocation = null;
            for (Directive d : server.block()) {
                switch (d.name()) {
                    case "listen" -> listen(d);
                    case "server_name" -> {
                    }
                    case "access_log", "error_log" -> change("import.change.logs", null);
                    case "location" -> {
                        if (d.args().equals(List.of("^~", "/.well-known/acme-challenge/"))) {
                            continue;
                        }
                        if (d.args().equals(List.of("/")) && d.isBlock()) {
                            rootLocation = d;
                        } else if (PHP_LOCATIONS.contains(d.args()) && d.isBlock()) {
                            php(d);
                        } else {
                            unsupported("import.reason.location", String.join(" ", d.args()));
                        }
                    }
                    case "return" -> redirect(d);
                    default -> common(d);
                }
            }
            if (rootLocation != null) {
                for (Directive d : rootLocation.block()) {
                    switch (d.name()) {
                        case "proxy_pass" -> proxyPass = d.arg(0);
                        case "proxy_http_version", "proxy_ssl_server_name", "proxy_ssl_name" -> {
                        }
                        case "proxy_set_header" -> header(d);
                        case "proxy_connect_timeout" -> connectTimeout = seconds(d);
                        case "proxy_read_timeout" -> readTimeout = seconds(d);
                        case "proxy_send_timeout" -> {
                            Integer send = seconds(d);
                            if (send != null && readTimeout != null && !send.equals(readTimeout)) {
                                change("import.change.sendTimeout", d.arg(0));
                            }
                        }
                        case "try_files" -> {
                        }
                        case "autoindex" -> autoindex = "on".equals(d.arg(0));
                        case "return" -> redirect(d);
                        default -> common(d);
                    }
                }
            }
            if (!unsupported.isEmpty()) {
                return null;
            }
            int kinds = (proxyPass != null ? 1 : 0) + (redirectTarget != null ? 1 : 0)
                    + (proxyPass == null && redirectTarget == null && root != null ? 1 : 0);
            if (fastcgiPass != null && (proxyPass != null || redirectTarget != null || root == null)) {
                unsupported("import.reason.mixedContent", null);
                return null;
            }
            if (proxyPass != null && redirectTarget != null) {
                unsupported("import.reason.mixedContent", null);
                return null;
            }
            if (kinds == 0) {
                unsupported("import.reason.noContent", null);
                return null;
            }
            SiteType type = proxyPass != null ? SiteType.PROXY : redirectTarget != null ? SiteType.REDIRECT
                    : fastcgiPass != null ? SiteType.PHP : SiteType.STATIC;
            boolean usesRoot = type == SiteType.STATIC || type == SiteType.PHP;
            if (!usesRoot && root != null) {
                change("import.change.root", root);
            }
            if (type != SiteType.STATIC && autoindex) {
                unsupported("import.reason.directive", "autoindex");
                return null;
            }
            String targets = null;
            LoadBalancing balancing = LoadBalancing.ROUND_ROBIN;
            if (type == SiteType.PROXY) {
                ProxyTargets resolved = proxyTargets(upstreams);
                if (resolved == null) {
                    return null;
                }
                targets = resolved.targets();
                balancing = resolved.balancing();
            } else if (type == SiteType.PHP && upstreams.isEmpty()) {
                targets = fastcgiPass;
            } else if (!upstreams.isEmpty()) {
                unsupported("import.reason.directive", "upstream");
                return null;
            }
            for (Directive map : maps) {
                if (!(websocket && type == SiteType.PROXY && "$http_upgrade".equals(map.arg(0)))) {
                    unsupported("import.reason.directive", "map " + String.join(" ", map.args()));
                    return null;
                }
            }
            boolean keepPath = redirectTarget != null && redirectTarget.endsWith("$request_uri");
            String redirect = keepPath
                    ? redirectTarget.substring(0, redirectTarget.length() - "$request_uri".length())
                    : redirectTarget;
            SiteOptions options = new SiteOptions(gzip, false, false, false, maxBodySizeMb, null, null, null, false,
                    null, null, null, null, null, null, autoindex, null, null, null, null, null, null, null, false, null, null,
                    null, null, false);
            String extra = String.join(" ", domains.subList(1, domains.size()));
            return new SiteForm(primary, extra, type, port == null ? 80 : port,
                    usesRoot ? root : null, targets, null, false, true, null,
                    type == SiteType.PROXY && websocket, type == SiteType.PROXY ? connectTimeout : null,
                    type == SiteType.PROXY ? readTimeout : null, balancing, redirect,
                    type == SiteType.REDIRECT ? redirectCode : null, keepPath, options);
        }

        /** PHP-FPM으로 넘기는 location(016). 폼이 만드는 것과 같은 역할의 지시어만 받는다. */
        private void php(Directive location) {
            for (Directive d : location.block()) {
                switch (d.name()) {
                    case "fastcgi_pass" -> fastcgiPass = d.arg(0);
                    case "include" -> {
                        if (!List.of("fastcgi_params", "fastcgi.conf", "snippets/fastcgi-php.conf").contains(d.arg(0))) {
                            unsupported("import.reason.directive", "include " + d.arg(0));
                        }
                    }
                    case "fastcgi_param" -> {
                        if (!"SCRIPT_FILENAME".equals(d.arg(0)) && !"HTTPS".equals(d.arg(0))) {
                            change("import.change.fastcgiParam", d.arg(0));
                        }
                    }
                    case "fastcgi_index", "fastcgi_split_path_info", "try_files", "fastcgi_read_timeout" -> {
                    }
                    default -> unsupported("import.reason.directive", d.name());
                }
            }
        }

        /** server와 location / 에 함께 둘 수 있는 지시어. */
        private void common(Directive d) {
            switch (d.name()) {
                case "root" -> {
                    if (root != null && !root.equals(d.arg(0))) {
                        unsupported("import.reason.directive", "root");
                    }
                    root = stripSlash(d.arg(0));
                }
                case "index" -> {
                    if (!d.args().equals(List.of("index.html")) && !d.args().equals(List.of("index.php", "index.html"))
                            && !d.args().equals(List.of("index.php"))) {
                        change("import.change.index", String.join(" ", d.args()));
                    }
                }
                case "client_max_body_size" -> maxBodySizeMb = megabytes(d);
                case "gzip" -> gzip = "on".equals(d.arg(0));
                case "autoindex" -> autoindex = "on".equals(d.arg(0));
                default -> {
                    if (d.name().startsWith("gzip_")) {
                        change("import.change.gzip", null);
                    } else {
                        unsupported("import.reason.directive", d.name());
                    }
                }
            }
        }

        private void listen(Directive d) {
            String address = d.arg(0);
            List<String> params = d.args().subList(1, d.args().size());
            if (params.contains("ssl") || params.contains("quic") || params.contains("http2")) {
                unsupported("import.reason.https", null);
                return;
            }
            if (!params.isEmpty()) {
                unsupported("import.reason.listen", String.join(" ", d.args()));
                return;
            }
            Integer value = RawListen.httpPort("listen " + address + ";").orElse(null);
            if (value == null) {
                unsupported("import.reason.listen", address);
                return;
            }
            String host = address.contains(":") ? address.substring(0, address.lastIndexOf(':')) : address;
            boolean ipv6 = address.startsWith("[");
            boolean any = !address.contains(":") && !address.contains(".") || host.equals("*") || host.equals("0.0.0.0");
            if (ipv6) {
                change("import.change.ipv6", address);
            } else if (!any) {
                unsupported("import.reason.listen", address);
                return;
            }
            if (port != null && !port.equals(value)) {
                unsupported("import.reason.listenPorts", port + ", " + value);
                return;
            }
            port = value;
        }

        private void redirect(Directive d) {
            if (d.args().size() != 2) {
                unsupported("import.reason.return", String.join(" ", d.args()));
                return;
            }
            try {
                redirectCode = Integer.valueOf(d.arg(0));
            } catch (NumberFormatException e) {
                unsupported("import.reason.return", String.join(" ", d.args()));
                return;
            }
            if (!SiteForm.REDIRECT_CODES.contains(redirectCode)) {
                unsupported("import.reason.return", String.join(" ", d.args()));
                return;
            }
            if (redirectTarget != null && !redirectTarget.equals(d.arg(1))) {
                unsupported("import.reason.return", String.join(" ", d.args()));
            }
            redirectTarget = d.arg(1);
        }

        private void header(Directive d) {
            String name = d.arg(0) == null ? "" : d.arg(0).toLowerCase(Locale.ROOT);
            if (FORM_HEADERS.contains(name)) {
                return;
            }
            if (name.equals("upgrade") || name.equals("connection")) {
                websocket = true;
                return;
            }
            unsupported("import.reason.header", d.arg(0));
        }

        private record ProxyTargets(String targets, LoadBalancing balancing) {
        }

        private ProxyTargets proxyTargets(Map<String, Directive> upstreams) {
            int schemeEnd = proxyPass.indexOf("://");
            if (schemeEnd < 0) {
                unsupported("import.reason.proxyPass", proxyPass);
                return null;
            }
            String scheme = proxyPass.substring(0, schemeEnd);
            String rest = proxyPass.substring(schemeEnd + 3);
            int slash = rest.indexOf('/');
            String host = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "" : rest.substring(slash);
            Directive upstream = upstreams.get(host);
            for (String name : upstreams.keySet()) {
                if (!name.equals(host)) {
                    unsupported("import.reason.directive", "upstream " + name);
                    return null;
                }
            }
            if (upstream == null) {
                return new ProxyTargets(proxyPass, LoadBalancing.ROUND_ROBIN);
            }
            List<String> targets = new ArrayList<>();
            LoadBalancing balancing = LoadBalancing.ROUND_ROBIN;
            for (Directive d : upstream.block()) {
                switch (d.name()) {
                    case "server" -> {
                        if (d.args().size() != 1) {
                            unsupported("import.reason.upstreamServer", String.join(" ", d.args()));
                            return null;
                        }
                        targets.add(scheme + "://" + d.arg(0) + path);
                    }
                    case "least_conn" -> balancing = LoadBalancing.LEAST_CONN;
                    case "ip_hash" -> balancing = LoadBalancing.IP_HASH;
                    default -> {
                        unsupported("import.reason.directive", d.name());
                        return null;
                    }
                }
            }
            if (targets.isEmpty()) {
                unsupported("import.reason.proxyPass", proxyPass);
                return null;
            }
            if (targets.size() == 1) {
                change("import.change.upstream", host);
            }
            return new ProxyTargets(String.join("\n", targets), balancing);
        }

        private Integer seconds(Directive d) {
            Matcher m = DURATION.matcher(d.arg(0) == null ? "" : d.arg(0));
            if (!m.matches()) {
                unsupported("import.reason.directive", d.name() + " " + d.arg(0));
                return null;
            }
            long value = Long.parseLong(m.group(1));
            String unit = m.group(2) == null ? "s" : m.group(2);
            long secs = switch (unit) {
                case "ms" -> -1;
                case "m" -> value * 60;
                case "h" -> value * 3600;
                default -> value;
            };
            if (secs < 1 || secs > 3600) {
                unsupported("import.reason.directive", d.name() + " " + d.arg(0));
                return null;
            }
            return (int) secs;
        }

        private Integer megabytes(Directive d) {
            Matcher m = SIZE.matcher(d.arg(0) == null ? "" : d.arg(0));
            if (!m.matches()) {
                unsupported("import.reason.directive", d.name() + " " + d.arg(0));
                return null;
            }
            long value = Long.parseLong(m.group(1));
            String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
            if (value == 0) {
                return 0;
            }
            long mb = switch (unit) {
                case "g" -> value * 1024;
                case "m" -> value;
                default -> -1;
            };
            if (mb < 0) {
                unsupported("import.reason.directive", d.name() + " " + d.arg(0));
                return null;
            }
            return (int) Math.min(mb, Integer.MAX_VALUE);
        }

        private static String stripSlash(String path) {
            return path != null && path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        }
    }
}
