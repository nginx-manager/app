package net.nginxmanager.site;

import net.nginxmanager.cert.CertificatePaths;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 추가 도메인을 대표 도메인으로 보내기(023). */
class PrimaryDomainRedirectTest {

    final SiteConfigRenderer renderer = new SiteConfigRenderer();

    private static SiteForm site(String extra, int port, boolean https, boolean redirect) {
        return new SiteForm("a.local", extra, SiteType.STATIC, port, "/srv/a", null, null, https, true, null, false,
                null, null, null, null, null, null, SiteModuleOptionsTest.options("redirectToPrimary", redirect,
                "allowFrom", "10.0.0.0/8"));
    }

    @Test
    void extraDomainsGetTheirOwnServerThatRedirectsButKeepsAcme() {
        String conf = renderer.render("a.local", site("www.a.local old.local", 80, false, true));
        assertThat(conf).contains("    server_name a.local;\n", """
                server {
                    listen 80;
                    server_name www.a.local old.local;
                """, """
                    location / {
                        return 301 http://a.local$request_uri;
                    }
                }
                """);
        String extra = conf.substring(conf.indexOf("server_name www.a.local"));
        assertThat(extra).contains("location ^~ /.well-known/acme-challenge/ {").doesNotContain("allow ", "deny all");
    }

    @Test
    void httpsSendsEveryExtraRequestToHttpsPrimary() {
        CertificatePaths tls = new CertificatePaths("/certs/a.local/fullchain.pem", "/certs/a.local/privkey.pem");
        String conf = renderer.render("a.local", site("www.a.local", 80, true, true), tls);
        assertThat(conf).contains("return 301 https://a.local$request_uri;\n    }\n}\n", """
                server {
                    listen 443 ssl;
                    http2 on;
                    server_name www.a.local;
                """, "    return 301 https://a.local$request_uri;\n}\n");
        assertThat(conf.split("server_name a.local;", -1)).hasSize(3);
    }

    @Test
    void otherPortsKeepThePortAndNothingChangesWithoutExtrasOrTheOption() {
        assertThat(renderer.render("a.local", site("www.a.local", 8080, false, true)))
                .contains("return 301 http://a.local:8080$request_uri;");
        assertThat(renderer.render("a.local", site("www.a.local", 80, false, false)))
                .contains("server_name a.local www.a.local;").doesNotContain("return 301");
        assertThat(renderer.render("a.local", site("", 80, false, true)))
                .doesNotContain("return 301").containsOnlyOnce("server {");
    }
}
