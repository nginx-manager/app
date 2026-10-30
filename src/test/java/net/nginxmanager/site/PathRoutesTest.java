package net.nginxmanager.site;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 경로별 라우팅 렌더링·검증(019). */
class PathRoutesTest {

    final SiteConfigRenderer renderer = new SiteConfigRenderer();
    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static SiteOptions routes(String value, Object... more) {
        Object[] keyValues = new Object[more.length + 2];
        keyValues[0] = "pathRoutes";
        keyValues[1] = value;
        System.arraycopy(more, 0, keyValues, 2, more.length);
        return SiteModuleOptionsTest.options(keyValues);
    }

    private static SiteForm staticSite(SiteOptions options) {
        return new SiteForm("app.local", "", SiteType.STATIC, 80, "/srv/www/app", null, null, false, true, null,
                false, null, null, null, null, null, null, options);
    }

    private static SiteForm proxy(String targets, SiteOptions options) {
        return new SiteForm("app.local", "", SiteType.PROXY, 80, null, targets, null, false, true, null, true, 5, 30,
                null, null, null, null, options);
    }

    @Test
    void staticSiteSendsApiToABackendAndKeepsFilesForTheRest() {
        SiteForm form = staticSite(routes("/api/ http://10.0.0.5:8080/\n\n/legacy https://old.example.com"));
        assertThat(validator.validate(form)).isEmpty();

        String conf = renderer.render("app.local", form);
        assertThat(conf).contains("""
                    location ^~ /api/ {
                        proxy_pass http://10.0.0.5:8080/;
                        proxy_http_version 1.1;
                        proxy_set_header Host $host;
                """, "location ^~ /legacy {", "proxy_pass https://old.example.com;", "proxy_ssl_server_name on;",
                "    location / {\n", "try_files $uri $uri/ =404;");
        // 경로 location이 기본 location보다 먼저 온다
        assertThat(conf.indexOf("location ^~ /api/")).isLessThan(conf.indexOf("location / {"));
        // 정적 사이트에는 웹소켓·타임아웃 설정이 없다
        assertThat(conf).doesNotContain("proxy_read_timeout", "Upgrade");
    }

    @Test
    void directoryRoutesUseAliasWithMatchingSlashes() {
        String conf = renderer.render("app.local", proxy("http://10.0.0.1:8080", routes("/files /srv/uploads")));
        assertThat(conf).contains("    location ^~ /files/ {\n        alias /srv/uploads/;\n    }\n");
    }

    @Test
    void proxyRoutesFollowTheSiteTimeoutsAndWebsocket() {
        String conf = renderer.render("app.local", proxy("http://10.0.0.1:8080", routes("/ws/ http://10.0.0.9:3000")));
        String route = conf.substring(conf.indexOf("location ^~ /ws/"), conf.indexOf("location / {"));
        assertThat(route).contains("proxy_set_header Upgrade $http_upgrade;",
                "proxy_set_header Connection $connection_upgrade_app_local;", "proxy_connect_timeout 5s;",
                "proxy_read_timeout 30s;");
    }

    @Test
    void accessRulesAndLimitsAlsoGuardEveryRoute() {
        SiteForm form = staticSite(routes("/api/ http://10.0.0.5:8080 \n/files/ /srv/files",
                "allowFrom", "10.0.0.0/8", "authUsers", "kim", "rateLimit", 10,
                "authRequestUrl", "http://auth.local/verify"));
        String conf = renderer.render("app.local", form);
        for (String path : new String[] {"location ^~ /api/ {", "location ^~ /files/ {", "location / {"}) {
            String block = conf.substring(conf.indexOf(path), conf.indexOf("    }\n", conf.indexOf(path)));
            assertThat(block).as(path).contains("allow 10.0.0.0/8;", "deny all;", "auth_basic \"app.local\";",
                    "auth_request /_nginx_manager_auth;", "limit_req zone=req_app_local");
        }
    }

    @Test
    void invalidRoutesAreRejected() {
        assertThat(routes("/ http://10.0.0.1").isPathRoutesValid()).as("root path").isFalse();
        assertThat(routes("api http://10.0.0.1").isPathRoutesValid()).as("no slash").isFalse();
        assertThat(routes("/api/").isPathRoutesValid()).as("no target").isFalse();
        assertThat(routes("/api/ http://10.0.0.1 extra").isPathRoutesValid()).as("three fields").isFalse();
        assertThat(routes("/a/$host http://10.0.0.1").isPathRoutesValid()).as("variable").isFalse();
        assertThat(routes("/a/ http://10.0.0.1/$uri").isPathRoutesValid()).as("variable in target").isFalse();
        assertThat(routes("/a/ ftp://10.0.0.1").isPathRoutesValid()).as("scheme").isFalse();
        assertThat(routes("/a/ /data/manager").isPathRoutesValid()).as("protected dir").isFalse();
        assertThat(routes("/a/ /srv/../etc").isPathRoutesValid()).as("dot dot").isFalse();
        assertThat(routes("/a/ http://127.0.0.1:9000").isPathRoutesValid()).as("manager port").isFalse();
        assertThat(routes("/a /srv/a\n/a/ http://y.local").isPathRoutesValid()).as("same location").isFalse();
        assertThat(routes("/a/ http://x.local\n/b/ /srv/b").isPathRoutesValid()).isTrue();
    }

    @Test
    void grpcSitesCannotUseRoutesAndOtherTypesDropThem() {
        SiteForm grpc = proxy("grpc://10.0.0.1:50051", routes("/api/ http://10.0.0.5:8080"));
        assertThat(grpc.isPathRoutesAllowed()).isFalse();

        assertThat(routes("/api/ http://10.0.0.5").forType(SiteType.PHP).pathRoutes()).isNull();
        assertThat(routes("/api/ http://10.0.0.5").forType(SiteType.REDIRECT).pathRoutes()).isNull();
        assertThat(routes("/api/ http://10.0.0.5").forType(SiteType.STATIC).pathRoutes()).isNotNull();
    }

    @Test
    void routesAreStoredNormalized() {
        SiteOptions stored = routes("  /api/   http://10.0.0.5:8080 \n\n /files /srv/f ").withoutPasswords();
        assertThat(stored.pathRoutes()).isEqualTo("/api/ http://10.0.0.5:8080\n/files /srv/f");

        java.util.Properties values = new java.util.Properties();
        stored.store(values, "opt.");
        assertThat(SiteOptions.load(values, "opt.").pathRoutes()).isEqualTo(stored.pathRoutes());
    }
}
