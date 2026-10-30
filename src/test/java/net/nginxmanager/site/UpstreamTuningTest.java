package net.nginxmanager.site;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 업스트림 세부 설정(백업, 장애 판정, keepalive) 렌더링·검증(020). */
class UpstreamTuningTest {

    final SiteConfigRenderer renderer = new SiteConfigRenderer();
    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static SiteForm proxy(String targets, boolean websocket, LoadBalancing balancing, Object... options) {
        return new SiteForm("app.local", "", SiteType.PROXY, 80, null, targets, null, false, true, null, websocket,
                null, null, balancing, null, null, null, SiteModuleOptionsTest.options(options));
    }

    @Test
    void backupAndFailureSettingsGoIntoTheUpstreamEvenWithOneTarget() {
        SiteForm form = proxy("http://10.0.0.1:8080", false, null, "backupTargets", "http://10.0.0.9:8080",
                "upstreamMaxFails", 3, "upstreamFailTimeout", 30);
        assertThat(validator.validate(form)).isEmpty();

        String conf = renderer.render("app.local", form);
        assertThat(conf).contains("""
                upstream backend_app_local {
                    server 10.0.0.1:8080 max_fails=3 fail_timeout=30s;
                    server 10.0.0.9:8080 max_fails=3 fail_timeout=30s backup;
                }
                """, "proxy_pass http://backend_app_local;");
        assertThat(conf).doesNotContain("keepalive", "Connection \"\"");
    }

    @Test
    void keepaliveComesAfterTheBalancingMethodAndClearsTheConnectionHeader() {
        String conf = renderer.render("app.local", proxy("https://a.internal\nhttps://b.internal", false,
                LoadBalancing.LEAST_CONN, "upstreamKeepalive", 32));
        assertThat(conf).contains("""
                upstream backend_app_local {
                    least_conn;
                    server a.internal:443;
                    server b.internal:443;
                    keepalive 32;
                }
                """, "proxy_set_header Connection \"\";", "proxy_ssl_name a.internal;");
    }

    @Test
    void keepaliveWithWebsocketKeepsUpgradesAndReusesTheRest() {
        String conf = renderer.render("app.local", proxy("http://10.0.0.1:8080", true, null,
                "upstreamKeepalive", 16));
        assertThat(conf).contains("    default upgrade;\n    ''      \"\";\n",
                "proxy_set_header Connection $connection_upgrade_app_local;");
        assertThat(conf).doesNotContain("close;", "proxy_set_header Connection \"\";");
    }

    @Test
    void grpcUpstreamsUseTheSameSettings() {
        String conf = renderer.render("app.local", proxy("grpc://10.0.0.1:50051", false, null,
                "backupTargets", "grpc://10.0.0.2:50051", "upstreamKeepalive", 8));
        assertThat(conf).contains("server 10.0.0.2:50051 backup;", "keepalive 8;", "grpc_pass grpc://backend_app_local;");
    }

    @Test
    void untunedSingleTargetsStayWithoutAnUpstream() {
        String conf = renderer.render("app.local", proxy("http://10.0.0.1:8080", false, null));
        assertThat(conf).doesNotContain("upstream ").contains("proxy_pass http://10.0.0.1:8080;");
    }

    @Test
    void backupsMustMatchTheUpstreamsAndCannotUseIpHash() {
        assertThat(proxy("http://10.0.0.1:8080", false, null, "backupTargets", "https://10.0.0.9")
                .isBackupTargetsValid()).as("scheme").isFalse();
        assertThat(proxy("http://10.0.0.1:8080/app", false, null, "backupTargets", "http://10.0.0.9/other")
                .isBackupTargetsValid()).as("path").isFalse();
        assertThat(proxy("http://10.0.0.1:8080", false, null, "backupTargets", "http://127.0.0.1:9000")
                .isBackupTargetsValid()).as("manager").isFalse();
        assertThat(proxy("http://10.0.0.1:8080", false, null, "backupTargets", "10.0.0.9:8080")
                .isBackupTargetsValid()).as("format").isFalse();
        assertThat(proxy("http://10.0.0.1:8080", false, LoadBalancing.IP_HASH, "backupTargets", "http://10.0.0.9")
                .isBackupWithoutIpHash()).isFalse();
        assertThat(validator.validate(proxy("http://10.0.0.1:8080", false, null, "upstreamKeepalive", 0)))
                .isNotEmpty();
    }

    @Test
    void otherTypesDropTheSettings() {
        SiteOptions options = SiteModuleOptionsTest.options("backupTargets", "http://10.0.0.9",
                "upstreamMaxFails", 3, "upstreamKeepalive", 4).forType(SiteType.STATIC);
        assertThat(options.upstreamTuned()).isFalse();
    }
}
