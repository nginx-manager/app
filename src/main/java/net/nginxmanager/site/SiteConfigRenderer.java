package net.nginxmanager.site;

import org.springframework.stereotype.Component;

/** 폼 값으로 사람이 읽을 수 있는 server 블록을 만든다. 값 검증은 {@link SiteForm}이 먼저 끝낸다. */
@Component
public class SiteConfigRenderer {

    static final String HEADER = "# nginx-manager: 폼으로 만든 사이트입니다. 원문을 고치면 이 사이트는 원문 편집 전용으로 바뀝니다.\n";

    public String render(String primaryDomain, SiteForm form) {
        StringBuilder out = new StringBuilder(HEADER);
        String serverNames = form.extraDomainsNormalized() == null
                ? primaryDomain
                : primaryDomain + " " + form.extraDomainsNormalized();
        out.append("server {\n")
                .append("    listen ").append(form.listenHttpPort()).append(";\n")
                .append("    server_name ").append(serverNames).append(";\n\n")
                .append("    access_log /var/log/nginx/sites/").append(primaryDomain).append(".access.log main;\n")
                .append("    error_log /var/log/nginx/sites/").append(primaryDomain).append(".error.log;\n\n")
                .append("    location ^~ /.well-known/acme-challenge/ {\n")
                .append("        root /var/lib/nginx-manager/acme-webroot;\n")
                .append("        default_type text/plain;\n")
                .append("    }\n\n");
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
        return out.append("}\n").toString();
    }
}
