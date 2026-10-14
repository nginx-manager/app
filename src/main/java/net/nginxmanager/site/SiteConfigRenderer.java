package net.nginxmanager.site;

import net.nginxmanager.cert.CertificatePaths;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** 폼 값으로 사람이 읽을 수 있는 server 블록을 만든다. 값 검증은 {@link SiteForm}이 먼저 끝낸다. */
@Component
public class SiteConfigRenderer {

    static final String HEADER = "# nginx-manager: 폼으로 만든 사이트입니다. 원문을 고치면 이 사이트는 원문 편집 전용으로 바뀝니다.\n";
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
        String serverNames = form.extraDomainsNormalized() == null
                ? primaryDomain
                : primaryDomain + " " + form.extraDomainsNormalized();
        out.append("server {\n")
                .append("    listen ").append(form.listenHttpPort()).append(";\n")
                .append("    server_name ").append(serverNames).append(";\n\n");
        logs(out, primaryDomain);
        out.append("    location ^~ /.well-known/acme-challenge/ {\n")
                .append("        root /var/lib/nginx-manager/acme-webroot;\n")
                .append("        default_type text/plain;\n")
                .append("    }\n\n");
        if (https && form.redirectHttpToHttps()) {
            out.append("    location / {\n")
                    .append("        return 301 https://$host$request_uri;\n")
                    .append("    }\n");
        } else {
            content(out, primaryDomain, form);
        }
        out.append("}\n");
        if (https) {
            out.append("\nserver {\n")
                    .append("    listen 443 ssl;\n")
                    .append("    http2 on;\n")
                    .append("    server_name ").append(serverNames).append(";\n\n")
                    .append("    ssl_certificate ").append(tls.certPath()).append(";\n")
                    .append("    ssl_certificate_key ").append(tls.keyPath()).append(";\n")
                    .append("    ssl_protocols TLSv1.2 TLSv1.3;\n\n");
            logs(out, primaryDomain);
            content(out, primaryDomain, form);
            out.append("}\n");
        }
        return out.toString();
    }

    private static void logs(StringBuilder out, String primaryDomain) {
        out.append("    access_log /var/log/nginx/sites/").append(primaryDomain).append(".access.log main;\n")
                .append("    error_log /var/log/nginx/sites/").append(primaryDomain).append(".error.log;\n\n");
    }

    private static void content(StringBuilder out, String primaryDomain, SiteForm form) {
        switch (form.type()) {
            case PROXY -> proxyLocation(out, primaryDomain, form);
            case STATIC -> out
                    .append("    root ").append(form.staticRoot()).append(";\n")
                    .append("    index index.html;\n\n")
                    .append("    location / {\n")
                    .append("        try_files $uri $uri/ =404;\n")
                    .append("    }\n");
            case RAW -> throw new IllegalArgumentException("raw sites are not rendered");
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
        if (form.proxyWebsocket()) {
            // 업그레이드 요청만 Connection: upgrade로 넘기고, 나머지는 보통 요청처럼 close로 넘긴다
            out.append("map $http_upgrade ").append(connectionVariable(primaryDomain)).append(" {\n")
                    .append("    default upgrade;\n")
                    .append("    ''      close;\n")
                    .append("}\n\n");
        }
    }

    private static void proxyLocation(StringBuilder out, String primaryDomain, SiteForm form) {
        List<String> targets = form.proxyTargets();
        String pass;
        if (targets.size() > 1) {
            ProxyBackend first = ProxyBackend.parse(targets.getFirst());
            pass = first.scheme() + "://" + upstreamName(primaryDomain) + first.path();
        } else {
            pass = targets.getFirst();
        }
        out.append("    location / {\n")
                .append("        proxy_pass ").append(pass).append(";\n")
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
