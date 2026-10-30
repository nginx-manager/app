package net.nginxmanager.site;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** gRPC 프록시와 외부 인증(auth_request) 렌더링·검증(018). */
class GrpcAndAuthRequestTest {

    final SiteConfigRenderer renderer = new SiteConfigRenderer();
    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static SiteForm proxy(String targets, SiteOptions options) {
        return new SiteForm("api.local", "", SiteType.PROXY, 80, null, targets, null, false, true, null, true, 5, 30,
                null, null, null, null, options);
    }

    private static SiteOptions authRequest(String url, String signIn) {
        return SiteModuleOptionsTest.options("authRequestUrl", url, "authSignInUrl", signIn);
    }

    @Test
    void grpcTargetsUseGrpcPassAndHttp2() {
        SiteForm form = proxy("grpc://10.0.0.1:50051", SiteModuleOptionsTest.options("cacheMinutes", 10));
        assertThat(validator.validate(form)).isEmpty();
        assertThat(form.grpc()).isTrue();

        String conf = renderer.render("api.local", form);
        assertThat(conf).contains("    http2 on;\n", "grpc_pass grpc://10.0.0.1:50051;",
                "grpc_set_header X-Forwarded-For $proxy_add_x_forwarded_for;", "grpc_connect_timeout 5s;",
                "grpc_read_timeout 30s;", "grpc_send_timeout 30s;");
        // 웹소켓·캐시·proxy_ 지시어는 gRPC에 쓰지 않는다
        assertThat(conf).doesNotContain("proxy_pass", "map $http_upgrade", "proxy_cache", "proxy_http_version");
    }

    @Test
    void severalGrpcsBackendsShareAnUpstreamWithSni() {
        String conf = renderer.render("api.local", proxy("grpcs://a.internal:443\ngrpcs://b.internal",
                SiteOptions.DEFAULTS));
        assertThat(conf).contains("upstream backend_api_local {", "server a.internal:443;", "server b.internal:443;",
                "grpc_pass grpcs://backend_api_local;", "grpc_ssl_server_name on;", "grpc_ssl_name a.internal;");
    }

    @Test
    void grpcTargetsMustNotHaveAPathOrMixWithHttp() {
        assertThat(proxy("grpc://10.0.0.1:50051/svc", SiteOptions.DEFAULTS).isProxyTargetValid()).isFalse();
        assertThat(proxy("grpc://10.0.0.1:50051\nhttp://10.0.0.2:8080", SiteOptions.DEFAULTS).isProxyTargetValid())
                .isFalse();
        assertThat(proxy("http://10.0.0.1:8080", SiteOptions.DEFAULTS).grpc()).isFalse();
    }

    @Test
    void authRequestAsksTheAuthServerAndSendsUnauthenticatedUsersToSignIn() {
        SiteForm form = proxy("http://10.0.0.1:8080",
                authRequest("http://oauth2-proxy:4180/oauth2/auth", "https://auth.example.com/oauth2/start"));
        assertThat(validator.validate(form)).isEmpty();

        String conf = renderer.render("api.local", form);
        assertThat(conf).contains("""
                    location = /_nginx_manager_auth {
                        internal;
                        proxy_pass http://oauth2-proxy:4180/oauth2/auth;
                        proxy_pass_request_body off;
                        proxy_set_header Content-Length "";
                """, """
                    location @nginx_manager_sign_in {
                        return 302 https://auth.example.com/oauth2/start?rd=$scheme://$host$request_uri;
                    }
                """, "        auth_request /_nginx_manager_auth;\n", "        error_page 401 = @nginx_manager_sign_in;\n",
                "proxy_set_header X-Auth-Request-User $nm_auth_user;");
    }

    @Test
    void signInUrlWithAQueryGetsAnAmpersandAndNoSignInMeansPlain401() {
        String withQuery = renderer.render("api.local", proxy("http://10.0.0.1:8080",
                authRequest("https://sso.example.com/api/verify", "https://sso.example.com/?lang=ko")));
        assertThat(withQuery).contains("return 302 https://sso.example.com/?lang=ko&rd=", "proxy_ssl_server_name on;");

        String plain = renderer.render("api.local", proxy("http://10.0.0.1:8080",
                authRequest("http://auth:9091/api/verify", "")));
        assertThat(plain).contains("auth_request /_nginx_manager_auth;").doesNotContain("error_page 401", "@nginx", "auth_basic");
    }

    @Test
    void authRequestGuardsEveryPhpLocationAndCombinesWithAllowList() {
        SiteForm php = new SiteForm("blog.local", "", SiteType.PHP, 80, "/srv/www/blog", "127.0.0.1:9000", null, false,
                true, null, false, null, null, null, null, null, null,
                SiteModuleOptionsTest.options("authRequestUrl", "http://auth:9091/api/verify", "allowFrom", "10.0.0.0/8",
                        "satisfyAny", true));
        String conf = renderer.render("blog.local", php);
        assertThat(conf.split("auth_request /_nginx_manager_auth;", -1)).hasSize(3);
        assertThat(conf).contains("satisfy any;");
    }

    @Test
    void invalidAuthUrlsAreRejected() {
        assertThat(authRequest("ftp://auth/verify", null).isAuthRequestUrlValid()).isFalse();
        assertThat(authRequest("http://auth/$host", null).isAuthRequestUrlValid()).isFalse();
        assertThat(authRequest("http://auth/verify", "https://a b").isAuthSignInUrlValid()).isFalse();
        // 인증 서버 없이 로그인 주소만 적으면 버린다
        assertThat(authRequest(null, "https://login").authSignInUrl()).isNull();
    }
}
