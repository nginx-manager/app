package net.nginxmanager.site;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import net.nginxmanager.cert.CertificatePaths;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 사이트 모듈 옵션(접근 제한, 속도 제한, 압축, 헤더, HTTP/3, 실제 IP, 캐시)과 리디렉션 유형의 설정 생성·검증. */
class SiteModuleOptionsTest {

    static final CertificatePaths TLS = new CertificatePaths("/etc/nginx/certs/a/fullchain.pem",
            "/etc/nginx/certs/a/privkey.pem");

    final SiteConfigRenderer renderer = new SiteConfigRenderer();
    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    static SiteOptions options(Map<String, Object> values) {
        return new SiteOptions((Boolean) values.get("gzip"), (Boolean) values.get("securityHeaders"),
                (Boolean) values.get("hsts"), (Boolean) values.get("http3"), (Integer) values.get("maxBodySizeMb"),
                (String) values.get("allowFrom"), (String) values.get("denyFrom"), (String) values.get("authUsers"),
                (Boolean) values.get("satisfyAny"), (Integer) values.get("rateLimit"),
                (Integer) values.get("rateBurst"), (Integer) values.get("connLimit"),
                (String) values.get("realIpFrom"), (SiteOptions.RealIpHeader) values.get("realIpHeader"),
                (Integer) values.get("cacheMinutes"), (Boolean) values.get("autoindex"),
                (String) values.get("authRequestUrl"), (String) values.get("authSignInUrl"),
                (String) values.get("pathRoutes"), (String) values.get("backupTargets"),
                (Integer) values.get("upstreamMaxFails"), (Integer) values.get("upstreamFailTimeout"),
                (Integer) values.get("upstreamKeepalive"), (Boolean) values.get("maintenance"),
                (String) values.get("maintenanceMessage"), (String) values.get("maintenanceAllowFrom"));
    }

    static SiteOptions options(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put((String) keyValues[i], keyValues[i + 1]);
        }
        return options(values);
    }

    static SiteForm proxy(boolean https, SiteOptions options) {
        return new SiteForm("app.local", "", SiteType.PROXY, 80, null, "http://10.0.0.1:8080", null, https, true,
                null, false, null, null, null, null, null, null, options);
    }

    static SiteForm staticSite(SiteOptions options) {
        return new SiteForm("docs.local", "", SiteType.STATIC, 80, "/srv/www/docs", null, null, false, true, null,
                false, null, null, null, null, null, null, options);
    }

    static SiteForm redirect(String target, Integer code, Boolean keepPath) {
        return new SiteForm("old.local", "", SiteType.REDIRECT, 80, null, null, null, false, true, null, false, null,
                null, null, target, code, keepPath, null);
    }

    Set<String> violations(SiteForm form) {
        return validator.validate(form).stream().map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    void defaultOptionsRenderTheSameConfigAsBefore() {
        SiteForm withDefaults = proxy(false, SiteOptions.DEFAULTS);
        SiteForm legacy = new SiteForm("app.local", "", SiteType.PROXY, 80, null, "http://10.0.0.1:8080", null);

        assertThat(renderer.render("app.local", withDefaults)).isEqualTo(renderer.render("app.local", legacy));
    }

    @Test
    void accessRulesGoInsideTheContentLocationOnly() {
        String config = renderer.render("app.local", proxy(false, options("allowFrom", "10.0.0.0/8, 192.168.1.7",
                "denyFrom", "10.1.0.0/16", "authUsers", "kim", "satisfyAny", true)));

        String acme = config.substring(config.indexOf("acme-challenge"), config.indexOf("location / {"));
        assertThat(acme).doesNotContain("allow", "deny", "auth_basic");
        assertThat(config).contains("""
                        satisfy any;
                        deny 10.1.0.0/16;
                        allow 10.0.0.0/8;
                        allow 192.168.1.7;
                        deny all;
                        auth_basic "app.local";
                        auth_basic_user_file htpasswd/app.local;
                """);
    }

    @Test
    void denyListAloneDoesNotDenyEveryoneElse() {
        String config = renderer.render("app.local", proxy(false, options("denyFrom", "203.0.113.0/24")));

        assertThat(config).contains("deny 203.0.113.0/24;").doesNotContain("deny all;", "satisfy");
    }

    @Test
    void rateAndConnectionLimitsDeclareZonesAtHttpLevel() {
        String config = renderer.render("my-app.local",
                new SiteForm("my-app.local", "", SiteType.PROXY, 80, null, "http://10.0.0.1:8080", null, false, true,
                        null, false, null, null, null, null, null, null,
                        options("rateLimit", 10, "rateBurst", 20, "connLimit", 5)));

        assertThat(config.indexOf("limit_req_zone")).isLessThan(config.indexOf("server {"));
        assertThat(config).contains(
                "limit_req_zone $binary_remote_addr zone=req_my__app_local:10m rate=10r/s;",
                "limit_conn_zone $binary_remote_addr zone=conn_my__app_local:10m;",
                "limit_req zone=req_my__app_local burst=20 nodelay;", "limit_req_status 429;",
                "limit_conn conn_my__app_local 5;", "limit_conn_status 429;");
    }

    @Test
    void serverLevelOptions() {
        String config = renderer.render("docs.local", staticSite(options("gzip", true, "maxBodySizeMb", 100,
                "realIpFrom", "10.0.0.0/8", "autoindex", true)));

        assertThat(config).contains("client_max_body_size 100m;", "gzip on;", "gzip_static on;",
                "set_real_ip_from 10.0.0.0/8;", "real_ip_header X-Forwarded-For;", "real_ip_recursive on;",
                "autoindex on;");
        assertThat(renderer.render("docs.local", staticSite(options("maxBodySizeMb", 0))))
                .contains("client_max_body_size 0;");
        assertThat(renderer.render("app.local", proxy(false, options("gzip", true,
                "realIpFrom", "173.245.48.0/20", "realIpHeader", SiteOptions.RealIpHeader.CF_CONNECTING_IP))))
                .contains("gzip on;", "real_ip_header CF-Connecting-IP;")
                .doesNotContain("gzip_static", "real_ip_recursive");
    }

    @Test
    void httpsOnlyHeadersAndHttp3GoInTheHttpsServer() {
        String config = renderer.render("app.local", proxy(true, options("securityHeaders", true, "hsts", true,
                "http3", true)), TLS);

        String https = config.substring(config.indexOf("listen 443 ssl;"));
        assertThat(https).contains("listen 443 quic;", "add_header Strict-Transport-Security \"max-age=31536000\" always;",
                "add_header Alt-Svc 'h3=\":443\"; ma=86400' always;", "add_header X-Content-Type-Options nosniff always;");
        assertThat(config.substring(0, config.indexOf("listen 443 ssl;"))).doesNotContain("Strict-Transport-Security");
        assertThat(config).doesNotContain("reuseport");
    }

    @Test
    void hstsAndHttp3NeedHttps() {
        assertThat(violations(proxy(false, options("hsts", true)))).contains("httpsOptionsValid");
        assertThat(violations(proxy(false, options("http3", true)))).contains("httpsOptionsValid");
        assertThat(violations(proxy(true, options("hsts", true, "http3", true)))).isEmpty();
    }

    @Test
    void proxyCache() {
        String config = renderer.render("app.local", proxy(false, options("cacheMinutes", 10)));

        assertThat(config).contains(
                "proxy_cache_path /var/cache/nginx/site-app.local levels=1:2 keys_zone=cache_app_local:10m max_size=1g inactive=60m use_temp_path=off;",
                "proxy_cache cache_app_local;", "proxy_cache_valid 200 301 302 10m;",
                "add_header X-Cache-Status $upstream_cache_status always;");
    }

    @Test
    void optionsThatDoNotFitTheTypeAreDropped() {
        SiteOptions both = options("cacheMinutes", 10, "autoindex", true);

        assertThat(both.forType(SiteType.STATIC).cacheMinutes()).isNull();
        assertThat(both.forType(SiteType.STATIC).autoindex()).isTrue();
        assertThat(both.forType(SiteType.PROXY).autoindex()).isFalse();
        assertThat(renderer.render("docs.local", staticSite(options("cacheMinutes", 10)))).doesNotContain("proxy_cache");
    }

    @Test
    void addressValidation() {
        assertThat(violations(proxy(false, options("allowFrom", "10.0.0.0/8 2001:db8::/32 ::1")))).isEmpty();
        assertThat(violations(proxy(false, options("allowFrom", "10.0.0.0/33")))).contains("options.allowFromValid");
        assertThat(violations(proxy(false, options("denyFrom", "all")))).contains("options.denyFromValid");
        assertThat(violations(proxy(false, options("realIpFrom", "10.0.0.1; deny all")))).contains("options.realIpFromValid");
    }

    @Test
    void ipv6AddressesAreCheckedLikeNginxDoes() {
        assertThat(List.of("::1", "::", "2001:db8::/32", "fe80::1:2", "1:2:3:4:5:6:7:8", "::ffff:10.0.0.1",
                "2001:DB8:0:0:0:0:0:1/128", "1::8"))
                .allSatisfy(a -> assertThat(SiteOptions.isAddress(a)).as(a).isTrue());
        // nginx -t가 거부하는 값은 저장할 때 막는다
        assertThat(List.of(":", "1::2::3", "::::/64", "1:2:3:4:5:6:7:8:9", "1:2:3", "12345::", "::1/129",
                "::ffff:10.0.0.256", "2001:db8::/", "fe80::1%eth0"))
                .allSatisfy(a -> assertThat(SiteOptions.isAddress(a)).as(a).isFalse());
    }

    @Test
    void authUserValidation() {
        assertThat(violations(proxy(false, options("authUsers", "kim:long-enough\nlee")))).isEmpty();
        assertThat(violations(proxy(false, options("authUsers", "kim:short")))).contains("options.authUsersValid");
        assertThat(violations(proxy(false, options("authUsers", "bad name:long-enough"))))
                .contains("options.authUsersValid");
    }

    @Test
    void passwordsNeverLeaveTheForm() {
        SiteOptions withPasswords = options("authUsers", "kim:secret-password\n lee \n");

        assertThat(withPasswords.withoutPasswords().authUsers()).isEqualTo("kim\nlee");
        assertThat(withPasswords.authEntries()).containsEntry("kim", "secret-password").containsEntry("lee", null);
    }

    @Test
    void htpasswdKeepsExistingHashesAndHashesNewPasswords() {
        String first = Htpasswd.merge(null, options("authUsers", "kim:password-one").authEntries());
        String kimHash = Htpasswd.parse(first).get("kim");
        assertThat(kimHash).startsWith("{SSHA}");
        assertThat(Htpasswd.matches("password-one", kimHash)).isTrue();
        assertThat(first).doesNotContain("password-one");

        String second = Htpasswd.merge(first, options("authUsers", "kim\nlee:password-two").authEntries());
        assertThat(Htpasswd.parse(second)).containsEntry("kim", kimHash).containsKey("lee");

        assertThat(Htpasswd.merge(second, Map.of())).isNull();
        assertThatThrownBy(() -> Htpasswd.merge(first, options("authUsers", "park").authEntries()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("park");
    }

    @Test
    void redirectSite() {
        assertThat(renderer.render("old.local", redirect("https://new.example.com/", 301, true)))
                .contains("return 301 https://new.example.com$request_uri;").doesNotContain("proxy_pass", "try_files");
        assertThat(renderer.render("old.local", redirect("https://new.example.com/landing", 302, false)))
                .contains("return 302 https://new.example.com/landing;");
    }

    @Test
    void redirectValidation() {
        assertThat(violations(redirect("https://new.example.com/a-b?x=1", 308, false))).isEmpty();
        assertThat(violations(redirect("https://new.example.com/a?x=1", 301, true))).contains("redirectKeepPathValid");
        assertThat(violations(redirect("https://new.example.com/$host", 301, true))).contains("redirectTargetValid");
        assertThat(violations(redirect("https://a.com/;return 200", 301, true))).contains("redirectTargetValid");
        assertThat(violations(redirect("javascript:alert(1)", 301, true))).contains("redirectTargetValid");
        assertThat(violations(redirect("https://new.example.com", 303, true))).contains("redirectCodeValid");
        assertThat(violations(redirect(null, 301, true))).contains("redirectTargetValid");
    }

    @Test
    void httpsRedirectSiteRedirectsOnBothServers() {
        SiteForm form = new SiteForm("old.local", "", SiteType.REDIRECT, 80, null, null, null, true, false, null,
                false, null, null, null, "https://new.example.com", 301, true, null);

        String config = renderer.render("old.local", form, TLS);
        assertThat(config.split("return 301 https://new.example.com\\$request_uri;", -1)).hasSize(3);
    }
}
