package net.nginxmanager.site;

import net.nginxmanager.cert.CertificatePaths;
import org.springframework.stereotype.Component;

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
            content(out, form);
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
            content(out, form);
            out.append("}\n");
        }
        return out.toString();
    }

    private static void logs(StringBuilder out, String primaryDomain) {
        out.append("    access_log /var/log/nginx/sites/").append(primaryDomain).append(".access.log main;\n")
                .append("    error_log /var/log/nginx/sites/").append(primaryDomain).append(".error.log;\n\n");
    }

    private static void content(StringBuilder out, SiteForm form) {
        switch (form.type()) {
            case STATIC -> out
                    .append("    root ").append(form.staticRoot()).append(";\n")
                    .append("    index index.html;\n\n")
                    .append("    location / {\n")
                    .append("        try_files $uri $uri/ =404;\n")
                    .append("    }\n");
            case PROXY -> out
                    .append("    location / {\n")
                    .append("        proxy_pass ").append(form.proxyTarget()).append(";\n")
                    .append("        proxy_http_version 1.1;\n")
                    .append("        proxy_set_header Host $host;\n")
                    .append("        proxy_set_header X-Real-IP $remote_addr;\n")
                    .append("        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n")
                    .append("        proxy_set_header X-Forwarded-Proto $scheme;\n")
                    .append("    }\n");
            case RAW -> throw new IllegalArgumentException("raw sites are not rendered");
        }
    }
}
