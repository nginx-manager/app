package net.nginxmanager.admin;

import net.nginxmanager.cert.CertificatePaths;

/**
 * 관리 화면 프록시 설정 파일의 내용. 인증서가 없으면 HTTP-01 챌린지만 받고 관리 화면은 열지 않는다.
 * 인증서가 있으면 80은 HTTPS로 넘기고(챌린지 제외) 443에서 관리 화면으로 프록시한다.
 */
public final class AdminConfigRenderer {

    /** 사이트 파일과 같은 디렉터리에 두어 기본 nginx.conf가 읽게 한다. 도메인은 _로 시작할 수 없어 사이트 파일과 겹치지 않는다. */
    public static final String PATH = "conf.d/sites/_nginx-manager-admin.conf";

    static final String WEBROOT = "/var/lib/nginx-manager/acme-webroot";

    private AdminConfigRenderer() {
    }

    public static String render(AdminDomainProperties props, CertificatePaths cert) {
        String domain = props.domain();
        StringBuilder out = new StringBuilder();
        out.append("# nginx manager 관리 화면(").append(domain).append("). 관리도구가 .env의 NGINX_MANAGER_ADMIN_DOMAIN으로 만든다.\n")
                .append("# 이 파일은 고치지 마세요. 바꾸려면 .env를 고치고 manager를 다시 띄웁니다.\n");
        out.append("server {\n")
                .append("    listen 80;\n")
                .append("    server_name ").append(domain).append(";\n\n")
                .append("    location ^~ /.well-known/acme-challenge/ {\n")
                .append("        root ").append(WEBROOT).append(";\n")
                .append("        default_type text/plain;\n")
                .append("    }\n\n")
                .append("    location / {\n");
        if (cert == null) {
            out.append("        # 인증서를 받기 전에는 관리 화면을 HTTP로 열지 않는다\n")
                    .append("        default_type text/plain;\n")
                    .append("        return 503 \"nginx manager: HTTPS certificate is not ready yet\\n\";\n");
        } else {
            String port = props.httpsPort() == 443 ? "" : ":" + props.httpsPort();
            out.append("        return 301 https://$host").append(port).append("$request_uri;\n");
        }
        out.append("    }\n}\n");
        if (cert == null) {
            return out.toString();
        }
        out.append("\nserver {\n")
                .append("    listen 443 ssl;\n")
                .append("    http2 on;\n")
                .append("    server_name ").append(domain).append(";\n\n")
                .append("    ssl_certificate ").append(cert.certPath()).append(";\n")
                .append("    ssl_certificate_key ").append(cert.keyPath()).append(";\n")
                .append("    add_header Strict-Transport-Security \"max-age=31536000\" always;\n\n");
        if (!props.allow().isEmpty()) {
            for (String cidr : props.allow()) {
                out.append("    allow ").append(cidr).append(";\n");
            }
            out.append("    deny all;\n\n");
        }
        out.append("    client_max_body_size 20m;\n\n")
                .append("    location / {\n")
                .append("        proxy_pass ").append(props.upstream()).append(";\n")
                .append("        proxy_http_version 1.1;\n")
                .append("        proxy_set_header Host $host;\n")
                .append("        proxy_set_header X-Real-IP $remote_addr;\n")
                .append("        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n")
                .append("        proxy_set_header X-Forwarded-Proto https;\n")
                .append("        proxy_set_header X-Forwarded-Port ").append(props.httpsPort()).append(";\n")
                .append("        # 실시간 로그 보기(SSE)\n")
                .append("        proxy_buffering off;\n")
                .append("        proxy_read_timeout 1h;\n")
                .append("    }\n}\n");
        return out.toString();
    }
}
