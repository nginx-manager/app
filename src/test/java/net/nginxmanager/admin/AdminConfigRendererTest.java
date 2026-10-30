package net.nginxmanager.admin;

import net.nginxmanager.cert.CertificatePaths;
import net.nginxmanager.site.SitePaths;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminConfigRendererTest {

    static final CertificatePaths CERT = new CertificatePaths("/etc/nginx/certs/nm.example.com/1/fullchain.pem",
            "/etc/nginx/certs/nm.example.com/1/privkey.pem");

    @Test
    void withoutCertificateOnlyTheChallengeIsServedAndTheAdminUiStaysClosed() {
        String config = AdminConfigRenderer.render(props(List.of(), 443), null);

        assertThat(config)
                .contains("server_name nm.example.com;")
                .contains("location ^~ /.well-known/acme-challenge/")
                .contains("return 503")
                .doesNotContain("listen 443")
                .doesNotContain("proxy_pass");
    }

    @Test
    void withCertificateHttpRedirectsAndHttpsProxiesToTheManager() {
        String config = AdminConfigRenderer.render(props(List.of(), 443), CERT);

        assertThat(config)
                .contains("return 301 https://$host$request_uri;")
                .contains("location ^~ /.well-known/acme-challenge/")
                .contains("listen 443 ssl;")
                .contains("ssl_certificate " + CERT.certPath() + ";")
                .contains("ssl_certificate_key " + CERT.keyPath() + ";")
                .contains("proxy_pass http://manager:9000;")
                .contains("proxy_set_header X-Forwarded-Proto https;")
                .contains("Strict-Transport-Security")
                .doesNotContain("deny all");
    }

    @Test
    void otherHttpsPortIsKeptInTheRedirectAndAllowListIsApplied() {
        String config = AdminConfigRenderer.render(props(List.of("10.0.0.0/8", "203.0.113.7"), 8443), CERT);

        assertThat(config)
                .contains("return 301 https://$host:8443$request_uri;")
                .contains("allow 10.0.0.0/8;\n    allow 203.0.113.7;\n    deny all;");
    }

    @Test
    void theFileIsNotMistakenForASite() {
        assertThat(SitePaths.domainOf(AdminConfigRenderer.PATH)).isNull();
        assertThat(SitePaths.domainOf("conf.d/sites/app.example.com.conf")).isEqualTo("app.example.com");
    }

    static AdminDomainProperties props(List<String> allow, int httpsPort) {
        return new AdminDomainProperties("NM.example.com ", "ops@example.com", allow, httpsPort, null, null);
    }
}
