package net.nginxmanager.site;

import net.nginxmanager.cert.CertificatePaths;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** 폼 값으로 사람이 읽을 수 있는 server 블록을 만든다. 값 검증은 {@link SiteForm}이 먼저 끝낸다. */
@Component
public class SiteConfigRenderer {

    static final String HEADER = "# nginx-manager: 폼으로 만든 사이트입니다. 원문을 고치면 이 사이트는 원문 편집 전용으로 바뀝니다.\n";
    static final String AUTH_LOCATION = "/_nginx_manager_auth";
    static final String SIGN_IN_LOCATION = "@nginx_manager_sign_in";
    static final String HTTPS_PENDING = "# HTTPS: 인증서가 준비되면 관리도구가 HTTPS 설정을 더해 자동으로 적용합니다.\n";

    public String render(String primaryDomain, SiteForm form) {
        return render(primaryDomain, form, null);
    }

    /** @param tls 사이트에 쓸 인증서. HTTPS를 켰어도 null이면 아직 발급 전이라 HTTP만 만든다 */
    public String render(String primaryDomain, SiteForm form, CertificatePaths tls) {
        StringBuilder out = new StringBuilder(HEADER);
        boolean https = form.httpsEnabled() && tls != null;
        if (form.httpsEnabled() && tls == null) {
            out.append(HTTPS_PENDING);
        }
        if (form.type() == SiteType.PROXY) {
            proxyPrelude(out, primaryDomain, form);
        }
        optionPrelude(out, primaryDomain, form);
        boolean redirectExtras = form.extraDomainsNormalized() != null && form.options().redirectToPrimary();
        String serverNames = form.extraDomainsNormalized() == null || redirectExtras
                ? primaryDomain
                : primaryDomain + " " + form.extraDomainsNormalized();
        out.append("server {\n")
                .append("    listen ").append(form.listenHttpPort()).append(";\n")
                .append("    server_name ").append(serverNames).append(";\n");
        if (form.grpc()) {
            // gRPC는 HTTP/2로만 오간다. 평문 포트에서는 h2c(사전 지식)로 받는다
            out.append("    http2 on;\n");
        }
        out.append("\n");
        logs(out, primaryDomain);
        serverOptions(out, form);
        out.append("    location ^~ /.well-known/acme-challenge/ {\n")
                .append("        root /var/lib/nginx-manager/acme-webroot;\n")
                .append("        default_type text/plain;\n")
                .append("    }\n\n");
        if (https && form.redirectHttpToHttps()) {
            out.append("    location / {\n")
                    .append("        return 301 https://$host$request_uri;\n")
                    .append("    }\n");
        } else {
            content(out, primaryDomain, form, false);
        }
        out.append("}\n");
        if (https) {
            out.append("\nserver {\n")
                    .append("    listen 443 ssl;\n");
            if (form.options().http3()) {
                // reuseport는 주소마다 한 번만 쓸 수 있어 nginx.conf의 443 기본 서버에 둔다
                out.append("    listen 443 quic;\n");
            }
            out.append("    http2 on;\n")
                    .append("    server_name ").append(serverNames).append(";\n\n")
                    .append("    ssl_certificate ").append(tls.certPath()).append(";\n")
                    .append("    ssl_certificate_key ").append(tls.keyPath()).append(";\n")
                    .append("    ssl_protocols TLSv1.2 TLSv1.3;\n\n");
            logs(out, primaryDomain);
            serverOptions(out, form);
            content(out, primaryDomain, form, true);
            out.append("}\n");
        }
        if (redirectExtras) {
            extraDomainRedirects(out, primaryDomain, form, https ? tls : null);
        }
        return out.toString();
    }

    /**
     * 추가 도메인을 대표 도메인으로 보내는 server 블록(023). 인증서 발급 경로는 그대로 받아
     * 같은 인증서에 추가 도메인을 넣어 발급·갱신할 수 있게 한다.
     */
    private static void extraDomainRedirects(StringBuilder out, String primaryDomain, SiteForm form,
                                             CertificatePaths tls) {
        String target = tls != null ? "https://" + primaryDomain
                : "http://" + primaryDomain + (form.listenHttpPort() == 80 ? "" : ":" + form.listenHttpPort());
        out.append("\n# 추가 도메인은 대표 도메인으로 보낸다\n")
                .append("server {\n")
                .append("    listen ").append(form.listenHttpPort()).append(";\n")
                .append("    server_name ").append(form.extraDomainsNormalized()).append(";\n\n");
        logs(out, primaryDomain);
        out.append("    location ^~ /.well-known/acme-challenge/ {\n")
                .append("        root /var/lib/nginx-manager/acme-webroot;\n")
                .append("        default_type text/plain;\n")
                .append("    }\n\n")
                .append("    location / {\n")
                .append("        return 301 ").append(target).append("$request_uri;\n")
                .append("    }\n")
                .append("}\n");
        if (tls != null) {
            out.append("\nserver {\n")
                    .append("    listen 443 ssl;\n")
                    .append("    http2 on;\n")
                    .append("    server_name ").append(form.extraDomainsNormalized()).append(";\n\n")
                    .append("    ssl_certificate ").append(tls.certPath()).append(";\n")
                    .append("    ssl_certificate_key ").append(tls.keyPath()).append(";\n")
                    .append("    ssl_protocols TLSv1.2 TLSv1.3;\n\n");
            logs(out, primaryDomain);
            out.append("    return 301 ").append(target).append("$request_uri;\n")
                    .append("}\n");
        }
    }

    private static void logs(StringBuilder out, String primaryDomain) {
        out.append("    access_log /var/log/nginx/sites/").append(primaryDomain).append(".access.log main;\n")
                .append("    error_log /var/log/nginx/sites/").append(primaryDomain).append(".error.log;\n\n");
    }

    private static void content(StringBuilder out, String primaryDomain, SiteForm form, boolean https) {
        maintenanceLocation(out, form.options());
        authRequestLocations(out, form.options());
        pathRouteLocations(out, primaryDomain, form, https);
        switch (form.type()) {
            case PROXY -> proxyLocation(out, primaryDomain, form, https);
            case STATIC -> {
                out.append("    root ").append(form.staticRoot()).append(";\n")
                        .append("    index index.html;\n\n")
                        .append("    location / {\n");
                locationOptions(out, primaryDomain, form, https);
                if (form.options().autoindex()) {
                    out.append("        autoindex on;\n");
                }
                out.append("        try_files $uri $uri/ =404;\n")
                        .append("    }\n");
            }
            case REDIRECT -> {
                out.append("    location / {\n");
                locationOptions(out, primaryDomain, form, https);
                out.append("        return ").append(form.redirectCode()).append(' ')
                        .append(form.redirectTargetNormalized())
                        .append(form.redirectKeepPath() ? "$request_uri" : "").append(";\n")
                        .append("    }\n");
            }
            case PHP -> phpLocations(out, primaryDomain, form, https);
            case RAW -> throw new IllegalArgumentException("raw sites are not rendered");
        }
    }

    static final String MAINTENANCE_LOCATION = "@nginx_manager_maintenance";
    static final String DEFAULT_MAINTENANCE_MESSAGE =
            "서비스 점검 중입니다. 잠시 후 다시 접속해 주세요.\nWe are down for maintenance. Please try again soon.";

    /**
     * 점검 모드(021): 내용 location이 돌려준 503을 안내 페이지로 바꾼다. 문구는 HTML로 이스케이프하고,
     * nginx 문자열을 깨는 문자(', \, $, {, })는 검증에서 막는다.
     */
    private static void maintenanceLocation(StringBuilder out, SiteOptions options) {
        if (!options.maintenance()) {
            return;
        }
        String message = options.maintenanceMessage() == null ? DEFAULT_MAINTENANCE_MESSAGE : options.maintenanceMessage();
        String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width\"><title>503</title></head>"
                + "<body style=\"font-family:sans-serif;max-width:40rem;margin:4rem auto;padding:0 1rem\"><p>"
                + String.join("<br>", message.lines().map(SiteConfigRenderer::escapeHtml).toList())
                + "</p></body></html>";
        out.append("    error_page 503 ").append(MAINTENANCE_LOCATION).append(";\n")
                .append("    location ").append(MAINTENANCE_LOCATION).append(" {\n")
                .append("        default_type text/html;\n")
                .append("        add_header Retry-After 600 always;\n")
                .append("        add_header Cache-Control no-store always;\n")
                .append("        return 503 '").append(html).append("';\n")
                .append("    }\n\n");
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String maintenanceVariable(String primaryDomain) {
        return "$nm_maintenance_" + identifier(primaryDomain);
    }

    /**
     * 경로별 라우팅(019): 경로마다 location ^~ 를 두고 프록시 주소나 디렉터리로 보낸다.
     * 기본 location과 같은 접근·속도·헤더 옵션을 걸어 경로로 빠져나가는 구멍이 없게 한다.
     */
    private static void pathRouteLocations(StringBuilder out, String primaryDomain, SiteForm form, boolean https) {
        if (form.type() != SiteType.PROXY && form.type() != SiteType.STATIC || form.grpc()) {
            return;
        }
        for (PathRoute route : form.options().pathRouteList()) {
            out.append("    location ^~ ").append(route.locationPath()).append(" {\n");
            locationOptions(out, primaryDomain, form, https);
            if (!route.proxy()) {
                out.append("        alias ").append(route.aliasDirectory()).append(";\n")
                        .append("    }\n\n");
                continue;
            }
            out.append("        proxy_pass ").append(route.target()).append(";\n")
                    .append("        proxy_http_version 1.1;\n")
                    .append("        proxy_set_header Host ").append(hostHeader(form)).append(";\n")
                    .append("        proxy_set_header X-Real-IP $remote_addr;\n")
                    .append("        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n")
                    .append("        proxy_set_header X-Forwarded-Proto $scheme;\n");
            customRequestHeaders(out, form);
            if (route.target().startsWith("https://")) {
                out.append("        proxy_ssl_server_name on;\n");
            }
            if (form.type() == SiteType.PROXY && form.proxyWebsocket()) {
                out.append("        proxy_set_header Upgrade $http_upgrade;\n")
                        .append("        proxy_set_header Connection ").append(connectionVariable(primaryDomain)).append(";\n");
            }
            if (form.type() == SiteType.PROXY && form.proxyConnectTimeout() != null) {
                out.append("        proxy_connect_timeout ").append(form.proxyConnectTimeout()).append("s;\n");
            }
            if (form.type() == SiteType.PROXY && form.proxyReadTimeout() != null) {
                out.append("        proxy_read_timeout ").append(form.proxyReadTimeout()).append("s;\n")
                        .append("        proxy_send_timeout ").append(form.proxyReadTimeout()).append("s;\n");
            }
            out.append("    }\n\n");
        }
    }

    /**
     * 외부 인증 서버에 묻는 내부 location(018). 본문은 넘기지 않고 원래 주소·메서드를 헤더로 알린다.
     * 401이면 로그인 주소로 보내고 돌아올 주소를 rd로 붙인다(oauth2-proxy, Authelia가 쓰는 이름).
     */
    private static void authRequestLocations(StringBuilder out, SiteOptions options) {
        if (!options.authRequestEnabled()) {
            return;
        }
        out.append("    location = ").append(AUTH_LOCATION).append(" {\n")
                .append("        internal;\n")
                .append("        proxy_pass ").append(options.authRequestUrl()).append(";\n")
                .append("        proxy_pass_request_body off;\n")
                .append("        proxy_set_header Content-Length \"\";\n")
                .append("        proxy_set_header Host $host;\n")
                .append("        proxy_set_header X-Original-URI $request_uri;\n")
                .append("        proxy_set_header X-Original-Method $request_method;\n")
                .append("        proxy_set_header X-Forwarded-Proto $scheme;\n")
                .append("        proxy_set_header X-Forwarded-Host $host;\n")
                .append("        proxy_set_header X-Forwarded-Uri $request_uri;\n")
                .append("        proxy_set_header X-Real-IP $remote_addr;\n");
        if (options.authRequestUrl().startsWith("https://")) {
            out.append("        proxy_ssl_server_name on;\n");
        }
        out.append("    }\n\n");
        if (options.authSignInUrl() != null) {
            String separator = options.authSignInUrl().contains("?") ? "&" : "?";
            out.append("    location ").append(SIGN_IN_LOCATION).append(" {\n")
                    .append("        return 302 ").append(options.authSignInUrl()).append(separator)
                    .append("rd=$scheme://$host$request_uri;\n")
                    .append("    }\n\n");
        }
    }

    /**
     * PHP 사이트(016): 없는 경로는 index.php(프런트 컨트롤러)로, .php 요청은 PHP-FPM으로 넘긴다.
     * nginx는 location마다 따로 고르므로 접근 제한·비밀번호 보호를 .php location에도 넣는다.
     */
    private static void phpLocations(StringBuilder out, String primaryDomain, SiteForm form, boolean https) {
        out.append("    root ").append(form.staticRoot()).append(";\n")
                .append("    index index.php index.html;\n\n")
                .append("    location / {\n");
        locationOptions(out, primaryDomain, form, https);
        out.append("        try_files $uri $uri/ /index.php?$query_string;\n")
                .append("    }\n\n")
                .append("    location ~ \\.php$ {\n");
        locationOptions(out, primaryDomain, form, https);
        out.append("        try_files $uri =404;\n")
                .append("        fastcgi_pass ").append(form.proxyTargets().getFirst()).append(";\n")
                .append("        fastcgi_index index.php;\n")
                .append("        include fastcgi_params;\n")
                .append("        fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;\n");
        if (https) {
            out.append("        fastcgi_param HTTPS on;\n");
        }
        if (form.proxyReadTimeout() != null) {
            out.append("        fastcgi_read_timeout ").append(form.proxyReadTimeout()).append("s;\n");
        }
        out.append("    }\n");
    }

    /**
     * http 수준에 한 번만 둘 수 있는 지시어: 속도·연결 제한 영역, 프록시 캐시 저장소. 이름은 사이트마다 겹치지 않는다.
     */
    private static void optionPrelude(StringBuilder out, String primaryDomain, SiteForm form) {
        SiteOptions options = form.options();
        String id = identifier(primaryDomain);
        boolean any = false;
        if (options.maintenance()) {
            // 점검 중 막을 요청(1)과 작업자 주소(0)를 나눈다(021)
            out.append("geo ").append(maintenanceVariable(primaryDomain)).append(" {\n")
                    .append("    default 1;\n");
            for (String address : options.maintenanceAllowList()) {
                out.append("    ").append(address).append(" 0;\n");
            }
            out.append("}\n");
            any = true;
        }
        if (options.rateLimit() != null) {
            out.append("limit_req_zone $binary_remote_addr zone=req_").append(id).append(":10m rate=")
                    .append(options.rateLimit()).append("r/s;\n");
            any = true;
        }
        if (options.connLimit() != null) {
            out.append("limit_conn_zone $binary_remote_addr zone=conn_").append(id).append(":10m;\n");
            any = true;
        }
        if (form.type() == SiteType.PROXY && !form.grpc() && options.cacheMinutes() != null) {
            out.append("proxy_cache_path /var/cache/nginx/site-").append(primaryDomain)
                    .append(" levels=1:2 keys_zone=cache_").append(id)
                    .append(":10m max_size=1g inactive=").append(Math.max(60, options.cacheMinutes()))
                    .append("m use_temp_path=off;\n");
            any = true;
        }
        if (any) {
            out.append("\n");
        }
    }

    /** server 블록 전체에 걸리는 설정: 요청 크기, 압축, 실제 클라이언트 IP. */
    private static void serverOptions(StringBuilder out, SiteForm form) {
        SiteOptions options = form.options();
        boolean any = false;
        if (options.maxBodySizeMb() != null) {
            out.append("    client_max_body_size ").append(options.maxBodySizeMb()).append(options.maxBodySizeMb() == 0 ? "" : "m")
                    .append(";\n");
            any = true;
        }
        if (options.gzip()) {
            out.append("    gzip on;\n")
                    .append("    gzip_vary on;\n")
                    .append("    gzip_proxied any;\n")
                    .append("    gzip_comp_level 5;\n")
                    .append("    gzip_min_length 1024;\n")
                    .append("    gzip_types text/plain text/css text/xml text/javascript application/javascript")
                    .append(" application/json application/xml application/rss+xml image/svg+xml;\n");
            if (form.type() == SiteType.STATIC) {
                out.append("    gzip_static on;\n");
            }
            any = true;
        }
        if (!options.realIpList().isEmpty()) {
            for (String address : options.realIpList()) {
                out.append("    set_real_ip_from ").append(address).append(";\n");
            }
            out.append("    real_ip_header ").append(options.realIpHeader().header()).append(";\n");
            if (options.realIpHeader() == SiteOptions.RealIpHeader.X_FORWARDED_FOR) {
                out.append("    real_ip_recursive on;\n");
            }
            any = true;
        }
        if (any) {
            out.append("\n");
        }
    }

    /**
     * 사이트 내용(location /)에 거는 설정: 접근 제한, 비밀번호, 속도 제한, 응답 헤더. 인증서 발급 경로에는 걸지 않는다.
     * add_header는 하위 블록에 하나라도 있으면 상위 것을 물려받지 않으므로 헤더는 모두 여기에 둔다.
     */
    private static void locationOptions(StringBuilder out, String primaryDomain, SiteForm form, boolean https) {
        SiteOptions options = form.options();
        String id = identifier(primaryDomain);
        boolean access = options.accessRestricted();
        boolean auth = options.authEnabled() || options.authRequestEnabled();
        if (options.maintenance()) {
            // rewrite 단계라 접근 제한·인증보다 먼저 503을 준다. if 안에서 return만 쓰는 것은 안전한 형태다
            out.append("        if (").append(maintenanceVariable(primaryDomain)).append(") {\n")
                    .append("            return 503;\n")
                    .append("        }\n");
        }
        if (access && auth) {
            out.append("        satisfy ").append(options.satisfyAny() ? "any" : "all").append(";\n");
        }
        for (String address : options.denyList()) {
            out.append("        deny ").append(address).append(";\n");
        }
        for (String address : options.allowList()) {
            out.append("        allow ").append(address).append(";\n");
        }
        if (!options.allowList().isEmpty()) {
            out.append("        deny all;\n");
        }
        if (options.authEnabled()) {
            out.append("        auth_basic \"").append(primaryDomain).append("\";\n")
                    .append("        auth_basic_user_file ").append(Htpasswd.path(primaryDomain)).append(";\n");
        }
        if (options.authRequestEnabled()) {
            out.append("        auth_request ").append(AUTH_LOCATION).append(";\n");
            if (options.authSignInUrl() != null) {
                out.append("        error_page 401 = ").append(SIGN_IN_LOCATION).append(";\n");
            }
            if (form.type() == SiteType.PROXY && !form.grpc()) {
                // 인증 서버가 알려 준 사용자를 백엔드에 넘긴다(oauth2-proxy, Authelia 등의 응답 헤더)
                out.append("        auth_request_set $nm_auth_user $upstream_http_x_auth_request_user;\n")
                        .append("        auth_request_set $nm_auth_email $upstream_http_x_auth_request_email;\n")
                        .append("        proxy_set_header X-Auth-Request-User $nm_auth_user;\n")
                        .append("        proxy_set_header X-Auth-Request-Email $nm_auth_email;\n");
            }
        }
        if (options.rateLimit() != null) {
            out.append("        limit_req zone=req_").append(id);
            if (options.rateBurst() != null && options.rateBurst() > 0) {
                out.append(" burst=").append(options.rateBurst()).append(" nodelay");
            }
            out.append(";\n        limit_req_status 429;\n");
        }
        if (options.connLimit() != null) {
            out.append("        limit_conn conn_").append(id).append(' ').append(options.connLimit()).append(";\n")
                    .append("        limit_conn_status 429;\n");
        }
        if (options.securityHeaders()) {
            out.append("        add_header X-Content-Type-Options nosniff always;\n")
                    .append("        add_header X-Frame-Options SAMEORIGIN always;\n")
                    .append("        add_header Referrer-Policy strict-origin-when-cross-origin always;\n");
        }
        if (https && options.hsts()) {
            out.append("        add_header Strict-Transport-Security \"max-age=31536000\" always;\n");
        }
        if (https && options.http3()) {
            out.append("        add_header Alt-Svc 'h3=\":443\"; ma=86400' always;\n");
        }
        if (form.type() == SiteType.PROXY && !form.grpc() && options.cacheMinutes() != null) {
            out.append("        proxy_cache cache_").append(id).append(";\n")
                    .append("        proxy_cache_valid 200 301 302 ").append(options.cacheMinutes()).append("m;\n")
                    .append("        proxy_cache_use_stale error timeout updating http_500 http_502 http_503 http_504;\n")
                    .append("        proxy_cache_lock on;\n")
                    .append("        add_header X-Cache-Status $upstream_cache_status always;\n");
        }
        for (CustomHeader header : options.responseHeaderList()) {
            out.append("        add_header ").append(header.name()).append(' ').append(header.quotedValue())
                    .append(" always;\n");
        }
    }

    /** 백엔드에 보낼 Host(022). 사용자 지정 헤더에 Host가 있으면 그 값을 쓴다. */
    private static String hostHeader(SiteForm form) {
        return proxyRequestHeaders(form).stream().filter(CustomHeader::isHost).findFirst()
                .map(CustomHeader::quotedValue).orElse("$host");
    }

    private static List<CustomHeader> proxyRequestHeaders(SiteForm form) {
        return form.type() == SiteType.PROXY ? form.options().requestHeaderList() : List.of();
    }

    /** 사용자 지정 백엔드 헤더(022). Host는 기본 Host 줄을 바꿔 쓰므로 여기서는 뺀다. */
    private static void customRequestHeaders(StringBuilder out, SiteForm form) {
        for (CustomHeader header : proxyRequestHeaders(form)) {
            if (!header.isHost()) {
                out.append("        proxy_set_header ").append(header.name()).append(' ')
                        .append(header.quotedValue()).append(";\n");
            }
        }
    }

    /**
     * server 블록보다 먼저 와야 하는 http 수준 설정: 백엔드가 여럿이면 upstream, 웹소켓이면 Connection 헤더 map.
     * 사이트 파일은 http 블록 안에서 include되므로 파일 맨 위에 둘 수 있다.
     */
    private static void proxyPrelude(StringBuilder out, String primaryDomain, SiteForm form) {
        List<String> targets = form.proxyTargets();
        SiteOptions options = form.options();
        if (form.usesUpstream()) {
            out.append("upstream ").append(upstreamName(primaryDomain)).append(" {\n");
            String directive = form.proxyLoadBalancing().directive();
            if (directive != null) {
                out.append("    ").append(directive).append(";\n");
            }
            String failure = (options.upstreamMaxFails() == null ? "" : " max_fails=" + options.upstreamMaxFails())
                    + (options.upstreamFailTimeout() == null ? "" : " fail_timeout=" + options.upstreamFailTimeout() + "s");
            for (String target : targets) {
                out.append("    server ").append(ProxyBackend.parse(target).server()).append(failure).append(";\n");
            }
            for (String target : options.backupTargetList()) {
                out.append("    server ").append(ProxyBackend.parse(target).server()).append(failure)
                        .append(" backup;\n");
            }
            if (options.upstreamKeepalive() != null) {
                // 분산 방식(least_conn 등) 뒤에 와야 한다
                out.append("    keepalive ").append(options.upstreamKeepalive()).append(";\n");
            }
            out.append("}\n\n");
        }
        if (form.proxyWebsocket() && !form.grpc()) {
            // 업그레이드 요청만 Connection: upgrade로 넘기고, 나머지는 보통 요청처럼 close로 넘긴다.
            // keepalive를 쓰면 연결을 다시 쓰도록 빈 값으로 넘긴다(020)
            out.append("map $http_upgrade ").append(connectionVariable(primaryDomain)).append(" {\n")
                    .append("    default upgrade;\n")
                    .append(options.upstreamKeepalive() == null ? "    ''      close;\n" : "    ''      \"\";\n")
                    .append("}\n\n");
        }
    }

    private static void proxyLocation(StringBuilder out, String primaryDomain, SiteForm form, boolean https) {
        List<String> targets = form.proxyTargets();
        String pass;
        if (form.usesUpstream()) {
            ProxyBackend first = ProxyBackend.parse(targets.getFirst());
            pass = first.scheme() + "://" + upstreamName(primaryDomain) + first.path();
        } else {
            pass = targets.getFirst();
        }
        if (form.grpc()) {
            grpcLocation(out, primaryDomain, form, https, pass);
            return;
        }
        out.append("    location / {\n");
        locationOptions(out, primaryDomain, form, https);
        out.append("        proxy_pass ").append(pass).append(";\n")
                .append("        proxy_http_version 1.1;\n")
                .append("        proxy_set_header Host ").append(hostHeader(form)).append(";\n")
                .append("        proxy_set_header X-Real-IP $remote_addr;\n")
                .append("        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n")
                .append("        proxy_set_header X-Forwarded-Proto $scheme;\n");
        customRequestHeaders(out, form);
        if (pass.startsWith("https://")) {
            // https 백엔드에 SNI를 보낸다(가상 호스트·CDN 백엔드는 SNI가 없으면 핸드셰이크를 거절한다).
            // upstream으로 묶으면 보낼 이름이 upstream 이름이 되므로 첫 백엔드의 호스트 이름을 쓴다
            out.append("        proxy_ssl_server_name on;\n");
            if (form.usesUpstream()) {
                out.append("        proxy_ssl_name ").append(ProxyBackend.parse(targets.getFirst()).host()).append(";\n");
            }
        }
        if (form.proxyWebsocket()) {
            out.append("        proxy_set_header Upgrade $http_upgrade;\n")
                    .append("        proxy_set_header Connection ").append(connectionVariable(primaryDomain)).append(";\n");
        } else if (form.options().upstreamKeepalive() != null) {
            // keepalive 연결을 다시 쓰려면 Connection: close를 보내지 않아야 한다(020)
            out.append("        proxy_set_header Connection \"\";\n");
        }
        if (form.proxyConnectTimeout() != null) {
            out.append("        proxy_connect_timeout ").append(form.proxyConnectTimeout()).append("s;\n");
        }
        if (form.proxyReadTimeout() != null) {
            out.append("        proxy_read_timeout ").append(form.proxyReadTimeout()).append("s;\n")
                    .append("        proxy_send_timeout ").append(form.proxyReadTimeout()).append("s;\n");
        }
        out.append("    }\n");
    }

    /** gRPC 백엔드(018): grpc_pass로 넘긴다. 헤더와 타임아웃도 grpc_ 지시어를 쓴다. */
    private static void grpcLocation(StringBuilder out, String primaryDomain, SiteForm form, boolean https,
                                     String pass) {
        List<String> targets = form.proxyTargets();
        out.append("    location / {\n");
        locationOptions(out, primaryDomain, form, https);
        out.append("        grpc_pass ").append(pass).append(";\n")
                .append("        grpc_set_header X-Real-IP $remote_addr;\n")
                .append("        grpc_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n")
                .append("        grpc_set_header X-Forwarded-Proto $scheme;\n");
        for (CustomHeader header : proxyRequestHeaders(form)) {
            out.append("        grpc_set_header ").append(header.name()).append(' ').append(header.quotedValue())
                    .append(";\n");
        }
        if (pass.startsWith("grpcs://")) {
            out.append("        grpc_ssl_server_name on;\n");
            if (form.usesUpstream()) {
                out.append("        grpc_ssl_name ").append(ProxyBackend.parse(targets.getFirst()).host()).append(";\n");
            }
        }
        if (form.proxyConnectTimeout() != null) {
            out.append("        grpc_connect_timeout ").append(form.proxyConnectTimeout()).append("s;\n");
        }
        if (form.proxyReadTimeout() != null) {
            out.append("        grpc_read_timeout ").append(form.proxyReadTimeout()).append("s;\n")
                    .append("        grpc_send_timeout ").append(form.proxyReadTimeout()).append("s;\n");
        }
        out.append("    }\n");
    }

    /**
     * 사이트마다 겹치지 않는 이름. 도메인의 . 은 _ 로, - 는 __ 로 바꾼다
     * (도메인 라벨은 - 로 시작·끝나지 않으므로 서로 다른 도메인이 같은 이름이 되지 않는다).
     */
    static String upstreamName(String primaryDomain) {
        return "backend_" + identifier(primaryDomain);
    }

    private static String connectionVariable(String primaryDomain) {
        return "$connection_upgrade_" + identifier(primaryDomain);
    }

    private static String identifier(String primaryDomain) {
        return primaryDomain.toLowerCase(Locale.ROOT).replace("-", "__").replace('.', '_');
    }
}
