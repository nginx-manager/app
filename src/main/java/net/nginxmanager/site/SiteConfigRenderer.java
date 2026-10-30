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
        String serverNames = form.extraDomainsNormalized() == null
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
        return out.toString();
    }

    private static void logs(StringBuilder out, String primaryDomain) {
        out.append("    access_log /var/log/nginx/sites/").append(primaryDomain).append(".access.log main;\n")
                .append("    error_log /var/log/nginx/sites/").append(primaryDomain).append(".error.log;\n\n");
    }

    private static void content(StringBuilder out, String primaryDomain, SiteForm form, boolean https) {
        authRequestLocations(out, form.options());
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
    }

    /**
     * server 블록보다 먼저 와야 하는 http 수준 설정: 백엔드가 여럿이면 upstream, 웹소켓이면 Connection 헤더 map.
     * 사이트 파일은 http 블록 안에서 include되므로 파일 맨 위에 둘 수 있다.
     */
    private static void proxyPrelude(StringBuilder out, String primaryDomain, SiteForm form) {
        List<String> targets = form.proxyTargets();
        if (targets.size() > 1) {
            out.append("upstream ").append(upstreamName(primaryDomain)).append(" {\n");
            String directive = form.proxyLoadBalancing().directive();
            if (directive != null) {
                out.append("    ").append(directive).append(";\n");
            }
            for (String target : targets) {
                out.append("    server ").append(ProxyBackend.parse(target).server()).append(";\n");
            }
            out.append("}\n\n");
        }
        if (form.proxyWebsocket() && !form.grpc()) {
            // 업그레이드 요청만 Connection: upgrade로 넘기고, 나머지는 보통 요청처럼 close로 넘긴다
            out.append("map $http_upgrade ").append(connectionVariable(primaryDomain)).append(" {\n")
                    .append("    default upgrade;\n")
                    .append("    ''      close;\n")
                    .append("}\n\n");
        }
    }

    private static void proxyLocation(StringBuilder out, String primaryDomain, SiteForm form, boolean https) {
        List<String> targets = form.proxyTargets();
        String pass;
        if (targets.size() > 1) {
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
                .append("        proxy_set_header Host $host;\n")
                .append("        proxy_set_header X-Real-IP $remote_addr;\n")
                .append("        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n")
                .append("        proxy_set_header X-Forwarded-Proto $scheme;\n");
        if (pass.startsWith("https://")) {
            // https 백엔드에 SNI를 보낸다(가상 호스트·CDN 백엔드는 SNI가 없으면 핸드셰이크를 거절한다).
            // upstream으로 묶으면 보낼 이름이 upstream 이름이 되므로 첫 백엔드의 호스트 이름을 쓴다
            out.append("        proxy_ssl_server_name on;\n");
            if (targets.size() > 1) {
                out.append("        proxy_ssl_name ").append(ProxyBackend.parse(targets.getFirst()).host()).append(";\n");
            }
        }
        if (form.proxyWebsocket()) {
            out.append("        proxy_set_header Upgrade $http_upgrade;\n")
                    .append("        proxy_set_header Connection ").append(connectionVariable(primaryDomain)).append(";\n");
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
        if (pass.startsWith("grpcs://")) {
            out.append("        grpc_ssl_server_name on;\n");
            if (targets.size() > 1) {
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
