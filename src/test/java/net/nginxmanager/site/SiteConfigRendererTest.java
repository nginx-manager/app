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

    private static SiteForm proxy(String targets, boolean websocket, Integer connect, Integer read, LoadBalancing lb) {
        return new SiteForm("app-1.example.com", "", SiteType.PROXY, 80, null, targets, null, false, true, null,
                websocket, connect, read, lb);
    }

    @Test
    void rendersUpstreamForSeveralBackends() {
        SiteForm form = proxy("http://10.0.0.1:8080/api\nhttp://10.0.0.2/api, http://10.0.0.1:8080/api", false, null,
                null, LoadBalancing.LEAST_CONN);

        String config = renderer.render("app-1.example.com", form);

        assertThat(config).startsWith(SiteConfigRenderer.HEADER + """
                upstream backend_app__1_example_com {
                    least_conn;
                    server 10.0.0.1:8080;
                    server 10.0.0.2:80;
                }

                server {
                """);
        assertThat(config).contains("        proxy_pass http://backend_app__1_example_com/api;\n")
                .doesNotContain("map $http_upgrade", "proxy_read_timeout");
        assertThat(form.proxyTargetNormalized()).isEqualTo("http://10.0.0.1:8080/api\nhttp://10.0.0.2/api");
    }

    @Test
    void roundRobinUpstreamHasNoMethodAndHttpsBackendsKeepPort443() {
        String config = renderer.render("app-1.example.com",
                proxy("https://a.internal https://b.internal:8443", false, null, null, null));

        assertThat(config).contains("    server a.internal:443;\n    server b.internal:8443;\n}")
                .contains("proxy_pass https://backend_app__1_example_com;")
                .doesNotContain("least_conn", "ip_hash");
    }

    @Test
    void rendersWebsocketAndTimeouts() {
        String config = renderer.render("app-1.example.com",
                proxy("http://127.0.0.1:3000", true, 5, 3600, null));

        assertThat(config).contains("""
                map $http_upgrade $connection_upgrade_app__1_example_com {
                    default upgrade;
                    ''      close;
                }
                """).contains("""
                        proxy_set_header Upgrade $http_upgrade;
                        proxy_set_header Connection $connection_upgrade_app__1_example_com;
                        proxy_connect_timeout 5s;
                        proxy_read_timeout 3600s;
                        proxy_send_timeout 3600s;
                    }
                """).contains("proxy_pass http://127.0.0.1:3000;").doesNotContain("upstream ");
    }

    @Test
    void httpsSiteRendersUpstreamOnceForBothServers() {
        SiteForm form = new SiteForm("app.local", "", SiteType.PROXY, 80, null, "http://a:1 http://b:1", null, true,
                false, null, true, null, null, null);

        String config = renderer.render("app.local", form,
                new net.nginxmanager.cert.CertificatePaths("/etc/nginx/certs/c.pem", "/etc/nginx/certs/k.pem"));

        assertThat(config.split("upstream backend_app_local", -1)).hasSize(2);
        assertThat(config.split("map \\$http_upgrade", -1)).hasSize(2);
        assertThat(config.split("proxy_pass http://backend_app_local;", -1)).hasSize(3);
    }

    @Test
    void upstreamNamesDoNotCollide() {
        assertThat(SiteConfigRenderer.upstreamName("a-b.com")).isNotEqualTo(SiteConfigRenderer.upstreamName("a.b-com"))
                .isNotEqualTo(SiteConfigRenderer.upstreamName("a.b.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://a:1 https://b:1", "http://a:1/x http://b:1/y", "http://a:1 b:1"})
    void rejectsBackendsThatCannotShareOneUpstream(String targets) {
        assertThat(validator.validate(proxy(targets, false, null, null, null))).isNotEmpty();
    }

    @Test
    void rejectsTooManyBackendsAndOutOfRangeTimeouts() {
        String many = java.util.stream.IntStream.rangeClosed(1, SiteForm.MAX_BACKENDS + 1)
                .mapToObj(i -> "http://10.0.0." + i + ":80").collect(java.util.stream.Collectors.joining("\n"));
        assertThat(validator.validate(proxy(many, false, null, null, null))).isNotEmpty();
        assertThat(validator.validate(proxy("http://a:1", false, 0, null, null))).isNotEmpty();
        assertThat(validator.validate(proxy("http://a:1", false, null, 3601, null))).isNotEmpty();
        assertThat(validator.validate(proxy("http://a:1", true, 1, 3600, LoadBalancing.IP_HASH))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {7070, 8081, 9000})
    void rejectsPortsTheManagerUses(int port) {
        assertThat(validator.validate(new SiteForm("app.local", "", SiteType.PROXY, port, null, "http://a:1", null)))
                .isNotEmpty();
    }

    @Test
    void httpsBackendsGetSni() {
        assertThat(renderer.render("app-1.example.com", proxy("https://api.example.com", false, null, null, null)))
                .contains("proxy_ssl_server_name on;")
                .doesNotContain("proxy_ssl_name");
        assertThat(renderer.render("app-1.example.com",
                proxy("https://a.internal https://b.internal:8443", false, null, null, null)))
                .contains("proxy_ssl_server_name on;")
                .contains("proxy_ssl_name a.internal;");
        assertThat(renderer.render("app-1.example.com", proxy("http://a.internal", false, null, null, null)))
                .doesNotContain("proxy_ssl");
    }

    @ParameterizedTest
    @ValueSource(ints = {443, 7070, 8081, 9000})
    void reservedPortsCannotBeHttpPort(int port) {
        SiteForm form = new SiteForm("app.local", "", SiteType.PROXY, port, null, "http://10.0.0.1:8080", null);

        assertThat(validator.validate(form)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:9000", "http://localhost:8081", "http://[::1]:9000",
            "http://127.0.0.1:7070", "http://nginx:7070", "http://manager:9000", "http://10.0.0.1:70000"})
    void managerAndInvalidPortsAreRejectedAsProxyTarget(String target) {
        SiteForm form = new SiteForm("app.local", "", SiteType.PROXY, 80, null, target, null);

        assertThat(validator.validate(form)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/data", "/data/", "/data/backups", "/", "/etc/nginx/certs", "/var/lib", "/proc/1"})
    void protectedDirectoriesCannotBeStaticRoot(String root) {
        SiteForm form = new SiteForm("app.local", "", SiteType.STATIC, 80, root, null, null);

        assertThat(validator.validate(form)).isNotEmpty();
    }

    @Test
    void ordinaryStaticRootIsAccepted() {
        SiteForm form = new SiteForm("app.local", "", SiteType.STATIC, 80, "/srv/www/app", null, null);

        assertThat(validator.validate(form)).isEmpty();
    }
}
