package net.nginxmanager.site;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SiteConfigRendererTest {

    final SiteConfigRenderer renderer = new SiteConfigRenderer();
    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rendersReverseProxy() {
        SiteForm form = new SiteForm("app.local", "www.app.local, api.app.local", SiteType.PROXY, 80, null,
                "http://127.0.0.1:8080", null);

        assertThat(renderer.render("app.local", form)).isEqualTo(SiteConfigRenderer.HEADER + """
                server {
                    listen 80;
                    server_name app.local www.app.local api.app.local;

                    access_log /var/log/nginx/sites/app.local.access.log main;
                    error_log /var/log/nginx/sites/app.local.error.log;

                    location ^~ /.well-known/acme-challenge/ {
                        root /var/lib/nginx-manager/acme-webroot;
                        default_type text/plain;
                    }

                    location / {
                        proxy_pass http://127.0.0.1:8080;
                        proxy_http_version 1.1;
                        proxy_set_header Host $host;
                        proxy_set_header X-Real-IP $remote_addr;
                        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
                        proxy_set_header X-Forwarded-Proto $scheme;
                    }
                }
                """);
    }

    @Test
    void rendersStaticSite() {
        SiteForm form = new SiteForm("docs.local", "", SiteType.STATIC, 8080, "/srv/www/docs", null, null);

        assertThat(renderer.render("docs.local", form))
                .contains("listen 8080;", "server_name docs.local;", "root /srv/www/docs;", "try_files $uri $uri/ =404;")
                .doesNotContain("proxy_pass");
    }

    @Test
    void validFormHasNoViolations() {
        assertThat(validator.validate(new SiteForm("app.local", "*.app.local", SiteType.PROXY, 80, null,
                "http://backend:8080/api", null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://a;}\nserver{", "http://a b", "ftp://a", "http://a:8080;"})
    void rejectsProxyTargetsThatCouldInjectConfig(String target) {
        SiteForm form = new SiteForm("app.local", "", SiteType.PROXY, 80, null, target, null);
        assertThat(validator.validate(form)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.local;", "app local", "*.app.local", "../etc", "-bad.local"})
    void rejectsBadPrimaryDomains(String domain) {
        SiteForm form = new SiteForm(domain, "", SiteType.PROXY, 80, null, "http://a:1", null);
        assertThat(validator.validate(form)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative/path", "/srv/../etc", "/srv/www; include /etc/passwd", ""})
    void rejectsBadStaticRoots(String root) {
        SiteForm form = new SiteForm("app.local", "", SiteType.STATIC, 80, root, null, null);
        assertThat(validator.validate(form)).isNotEmpty();
    }
}
