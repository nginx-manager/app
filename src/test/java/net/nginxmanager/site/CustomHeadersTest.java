package net.nginxmanager.site;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 사용자 지정 헤더 렌더링·검증(022). */
class CustomHeadersTest {

    final SiteConfigRenderer renderer = new SiteConfigRenderer();
    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static SiteForm proxy(String targets, Object... options) {
        return new SiteForm("app.local", "", SiteType.PROXY, 80, null, targets, null, false, true, null, false, null,
                null, null, null, null, null, SiteModuleOptionsTest.options(options));
    }

    @Test
    void responseHeadersGoIntoEveryContentLocationQuoted() {
        SiteForm form = proxy("http://10.0.0.1:8080", "responseHeaders",
                "Access-Control-Allow-Origin: *\nContent-Security-Policy: default-src 'self'; img-src * {x}",
                "securityHeaders", true, "pathRoutes", "/files/ /srv/files");
        assertThat(validator.validate(form)).isEmpty();

        String conf = renderer.render("app.local", form);
        for (String path : new String[] {"location ^~ /files/ {", "location / {"}) {
            String block = conf.substring(conf.indexOf(path), conf.indexOf("\n    }\n", conf.indexOf(path)));
            assertThat(block).as(path).contains("add_header X-Frame-Options SAMEORIGIN always;",
                    "add_header Access-Control-Allow-Origin \"*\" always;",
                    "add_header Content-Security-Policy \"default-src 'self'; img-src * {x}\" always;");
        }
    }

    @Test
    void requestHeadersReplaceHostAndFollowProxyRoutes() {
        SiteForm form = proxy("http://10.0.0.1:8080", "requestHeaders",
                "Host: api.internal\nX-Tenant: $host\nX-Debug:", "pathRoutes", "/v2/ http://10.0.0.2:8080");
        assertThat(validator.validate(form)).isEmpty();

        String conf = renderer.render("app.local", form);
        for (String path : new String[] {"location ^~ /v2/ {", "location / {"}) {
            String block = conf.substring(conf.indexOf(path), conf.indexOf("\n    }\n", conf.indexOf(path)));
            assertThat(block).as(path).contains("proxy_set_header Host \"api.internal\";",
                    "proxy_set_header X-Tenant \"$host\";", "proxy_set_header X-Debug \"\";")
                    .doesNotContain("proxy_set_header Host $host;");
        }
    }

    @Test
    void grpcUsesGrpcSetHeaderAndOtherTypesDropRequestHeaders() {
        String conf = renderer.render("app.local", proxy("grpc://10.0.0.1:50051", "requestHeaders", "X-Tenant: a"));
        assertThat(conf).contains("grpc_set_header X-Tenant \"a\";");

        SiteOptions options = SiteModuleOptionsTest.options("requestHeaders", "X-Tenant: a", "responseHeaders", "X-A: b");
        assertThat(options.forType(SiteType.STATIC).requestHeaders()).isNull();
        assertThat(options.forType(SiteType.STATIC).responseHeaders()).isEqualTo("X-A: b");
        String staticConf = renderer.render("docs.local", SiteModuleOptionsTest.staticSite(options));
        assertThat(staticConf).contains("add_header X-A \"b\" always;").doesNotContain("X-Tenant");
    }

    @Test
    void invalidHeadersAreRejected() {
        for (String bad : new String[] {"X-A \"b\"", "X-A: say \"hi\"", "X-A: a\\b", "1X: a", "X A: b", "X-A",
                "X-A: a\nx-a: b", "X-A: cost$"}) {
            assertThat(SiteModuleOptionsTest.options("responseHeaders", bad).isResponseHeadersValid()).as(bad).isFalse();
        }
        assertThat(SiteModuleOptionsTest.options("responseHeaders", "X-A: " + "x".repeat(501))
                .isResponseHeadersValid()).isFalse();
        assertThat(SiteModuleOptionsTest.options("responseHeaders", java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> "X-" + i + ": a").collect(java.util.stream.Collectors.joining("\n")))
                .isResponseHeadersValid()).isFalse();
        for (String managed : new String[] {"X-Forwarded-For: 1.2.3.4", "connection: close", "Upgrade: h2c"}) {
            assertThat(SiteModuleOptionsTest.options("requestHeaders", managed).isRequestHeadersValid())
                    .as(managed).isFalse();
            assertThat(SiteModuleOptionsTest.options("responseHeaders", managed).isResponseHeadersValid())
                    .as(managed).isTrue();
        }
    }

    @Test
    void headersAreStoredNormalized() {
        SiteOptions stored = SiteModuleOptionsTest.options("responseHeaders", "  X-A :  b \n\n X-Empty:  ",
                "requestHeaders", "Host:api.internal").withoutPasswords();
        assertThat(stored.responseHeaders()).isEqualTo("X-A: b\nX-Empty:");
        assertThat(stored.requestHeaders()).isEqualTo("Host: api.internal");

        java.util.Properties values = new java.util.Properties();
        stored.store(values, "opt.");
        SiteOptions loaded = SiteOptions.load(values, "opt.");
        assertThat(loaded.responseHeaders()).isEqualTo(stored.responseHeaders());
        assertThat(loaded.requestHeaders()).isEqualTo(stored.requestHeaders());
    }
}
