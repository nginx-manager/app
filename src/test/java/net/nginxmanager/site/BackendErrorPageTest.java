package net.nginxmanager.site;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 백엔드 장애 안내 페이지(026). */
class BackendErrorPageTest {

    final SiteConfigRenderer renderer = new SiteConfigRenderer();

    private static SiteForm proxy(String target, Object... options) {
        return new SiteForm("app.local", "", SiteType.PROXY, 80, null, target, null, false, true, null, false, null,
                null, null, null, null, null, SiteModuleOptionsTest.options(options));
    }

    @Test
    void nginxGenerated502And504ShowTheNotice() {
        String conf = renderer.render("app.local", proxy("http://10.0.0.1:8080", "backendErrorPage", true,
                "backendErrorMessage", "점검 <곧> 끝남", "maintenance", true));
        assertThat(conf).contains("    error_page 502 504 @nginx_manager_backend_down;\n",
                "location @nginx_manager_backend_down {", "add_header Retry-After 30 always;",
                "return 502 '<!doctype html>", "<p>점검 &lt;곧&gt; 끝남</p>",
                // 점검 모드의 503과 겹치지 않는다
                "    error_page 503 @nginx_manager_maintenance;\n");
        assertThat(conf).doesNotContain("proxy_intercept_errors");
    }

    @Test
    void defaultMessageAndPhpSites() {
        assertThat(renderer.render("app.local", proxy("http://10.0.0.1:8080", "backendErrorPage", true)))
                .contains("The service is temporarily unavailable.");
        SiteForm php = new SiteForm("blog.local", "", SiteType.PHP, 80, "/srv/blog", "127.0.0.1:9000", null, false,
                true, null, false, null, null, null, null, null, null,
                SiteModuleOptionsTest.options("backendErrorPage", true));
        assertThat(renderer.render("blog.local", php)).contains("error_page 502 504 @nginx_manager_backend_down;");
    }

    @Test
    void grpcAndStaticSitesDoNotGetIt() {
        assertThat(renderer.render("app.local", proxy("grpc://10.0.0.1:50051", "backendErrorPage", true)))
                .doesNotContain("backend_down");
        SiteOptions options = SiteModuleOptionsTest.options("backendErrorPage", true, "backendErrorMessage", "x");
        assertThat(options.forType(SiteType.STATIC).backendErrorPage()).isFalse();
        assertThat(options.forType(SiteType.STATIC).backendErrorMessage()).isNull();
        assertThat(renderer.render("docs.local", SiteModuleOptionsTest.staticSite(options)))
                .doesNotContain("backend_down");
        assertThat(SiteModuleOptionsTest.options("backendErrorMessage", "it's").isBackendErrorMessageValid())
                .isFalse();
    }
}
