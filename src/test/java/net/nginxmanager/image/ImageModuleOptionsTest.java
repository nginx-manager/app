package net.nginxmanager.image;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 사이트·스트림 폼으로 켠 nginx 모듈 옵션(접근 제한, 비밀번호, 속도 제한, 요청 크기, 리디렉션, 프록시 캐시,
 * 디렉터리 목록, HTTP/3, 스트림)이 실제 nginx에서 검증을 통과하고 그대로 동작하는지 확인한다(T122, SC-102).
 * 테스트마다 사이트를 만들고 적용하므로 한 옵션이 깨져도 어느 것인지 바로 보인다.
 */
@Tag("image")
class ImageModuleOptionsTest {

    static Network network;
    static GenericContainer<?> pebble;
    static Stack stack;
    static ManagerClient client;

    @BeforeAll
    static void start() throws Exception {
        // HTTP/3는 HTTPS 사이트에서만 켤 수 있으므로 ImageCertificateTest처럼 Pebble에서 인증서를 받는다
        network = Network.newNetwork();
        pebble = new GenericContainer<>("ghcr.io/letsencrypt/pebble:2.10.1")
                .withNetwork(network)
                .withNetworkAliases("pebble")
                .withCommand("-config", "/test/config/pebble-config.json", "-strict")
                .withEnv("PEBBLE_VA_ALWAYS_VALID", "1")
                .withEnv("PEBBLE_WFE_NONCEREJECT", "0")
                .waitingFor(Wait.forLogMessage(".*ACME directory available.*", 1));
        pebble.start();
        stack = Stack.start(network,
                manager -> manager.withEnv("NGINX_MANAGER_ACME_DIRECTORY_URL", "acme://pebble/pebble:14000"));
        client = stack.client().setupAndLogin();
    }

    @AfterAll
    static void stop() {
        stack.close();
        pebble.stop();
        network.close();
    }

    @Test
    @DisplayName("허용 목록 밖이거나 차단한 주소는 403, 허용한 주소는 200")
    void ipAllowAndDeny() throws Exception {
        createStatic("allow-ok.local", "/etc/nginx/html", Map.of("options.allowFrom", "127.0.0.1"));
        createStatic("allow-other.local", "/etc/nginx/html", Map.of("options.allowFrom", "10.0.0.0/8"));
        createStatic("deny.local", "/etc/nginx/html", Map.of("options.denyFrom", "127.0.0.0/8"));
        apply("ip access");

        awaitStatus("allow-ok.local", "200");
        assertThat(status("allow-other.local")).isEqualTo("403");
        assertThat(status("deny.local")).isEqualTo("403");
    }

    @Test
    @DisplayName("비밀번호 보호: 없거나 틀리면 401, 맞으면 200({SSHA} 해시를 nginx가 읽는다)")
    void basicAuthWithSshaHash() throws Exception {
        createStatic("auth.local", "/etc/nginx/html", Map.of("options.authUsers", "kim:correct-pass-1"));
        apply("basic auth");

        awaitStatus("auth.local", "401");
        assertThat(stack.nginxExec("cat", "/etc/nginx/htpasswd/auth.local").getStdout())
                .startsWith("kim:{SSHA}").doesNotContain("correct-pass-1");
        assertThat(status("auth.local", "-u", "kim:wrong-pass-1")).isEqualTo("401");
        assertThat(status("auth.local", "-u", "kim:correct-pass-1")).isEqualTo("200");
    }

    @Test
    @DisplayName("속도 제한을 넘는 요청은 429로 거절된다")
    void rateLimitRejectsBurst() throws Exception {
        createStatic("rate.local", "/etc/nginx/html", Map.of("options.rateLimit", "1", "options.rateBurst", "1"));
        apply("rate limit");
        awaitStatus("rate.local", "200");

        String codes = stack.nginxExec("sh", "-c", "for i in 1 2 3 4 5 6 7 8 9 10; do "
                + "curl --noproxy '*' -s -o /dev/null -w '%{http_code} ' -H 'Host: rate.local' http://127.0.0.1/; "
                + "done").getStdout();
        List<String> statuses = Arrays.asList(codes.trim().split("\\s+"));
        assertThat(statuses).as(codes).contains("200").contains("429").doesNotContain("503");
    }

    @Test
    @DisplayName("최대 요청 크기를 넘는 업로드는 413")
    void maxBodySizeRejectsLargeUpload() throws Exception {
        createStatic("body.local", "/etc/nginx/html", Map.of("options.maxBodySizeMb", "1"));
        apply("max body size");
        awaitStatus("body.local", "200");

        String large = stack.nginxExec("sh", "-c", "head -c 2000000 /dev/zero | curl --noproxy '*' -s -o /dev/null "
                + "-w '%{http_code}' -H 'Host: body.local' --data-binary @- http://127.0.0.1/").getStdout();
        assertThat(large).isEqualTo("413");
        String small = stack.nginxExec("sh", "-c", "head -c 1000 /dev/zero | curl --noproxy '*' -s -o /dev/null "
                + "-w '%{http_code}' -H 'Host: body.local' --data-binary @- http://127.0.0.1/").getStdout();
        assertThat(small).isNotEqualTo("413");
    }

    @Test
    @DisplayName("리다이렉트 사이트는 경로와 쿼리를 유지해 넘긴다")
    void redirectKeepsPathAndQuery() throws Exception {
        create("old.local", "REDIRECT", Map.of("redirectTarget", "https://new.example.com/",
                "redirectCode", "308", "redirectKeepPath", "true"));
        apply("redirect");
        awaitStatus("old.local", "308");

        String headers = stack.nginxExec("curl", "--noproxy", "*", "-s", "-o", "/dev/null", "-D", "-",
                "-H", "Host: old.local", "http://127.0.0.1/a/b?c=1&d=2").getStdout();
        assertThat(headers).containsIgnoringCase("Location: https://new.example.com/a/b?c=1&d=2");
    }

    @Test
    @DisplayName("프록시 캐시: 두 번째 요청은 X-Cache-Status HIT")
    void proxyCacheHitsOnSecondRequest() throws Exception {
        stack.nginxExec("sh", "-c", "mkdir -p /tmp/origin && echo cached-body > /tmp/origin/index.html");
        createStatic("origin.local", "/tmp/origin", Map.of("listenHttpPort", "18181"));
        create("cache.local", "PROXY", Map.of("proxyTarget", "http://127.0.0.1:18181", "options.cacheMinutes", "10"));
        apply("proxy cache");
        awaitStatus("cache.local", "200");

        String second = cacheStatus("/index.html");
        String third = cacheStatus("/index.html");
        assertThat(second + third).as("first request fills the cache").contains("HIT");
        assertThat(third).isEqualTo("HIT");
    }

    @Test
    @DisplayName("디렉터리 리스팅: index.html이 없는 폴더는 파일 목록을 보여 준다")
    void autoindexListsFiles() throws Exception {
        stack.nginxExec("sh", "-c", "mkdir -p /tmp/listing/docs && echo x > /tmp/listing/docs/report-2026.txt");
        createStatic("files.local", "/tmp/listing", Map.of("options.autoindex", "true"));
        apply("autoindex");
        awaitStatus("files.local", "200");

        String body = stack.nginxExec("curl", "--noproxy", "*", "-s", "-H", "Host: files.local",
                "http://127.0.0.1/docs/").getStdout();
        assertThat(body).contains("Index of /docs/").contains("report-2026.txt");
    }

    @Test
    @DisplayName("관리자 추가 지시어는 server 블록에 들어가 동작하고, 메모는 설정 파일 주석으로 남는다(031, 032)")
    void serverDirectivesAndNote() throws Exception {
        createStatic("extra.local", "/etc/nginx/html", Map.of(
                "options.serverDirectives", "location = /healthz {\n    default_type text/plain;\n    return 200 'healthy';\n}",
                "options.note", "결제 팀 운영"));
        apply("server directives");
        awaitStatus("extra.local", "200");

        String body = stack.nginxExec("curl", "--noproxy", "*", "-s", "-H", "Host: extra.local",
                "http://127.0.0.1/healthz").getStdout();
        assertThat(body).isEqualTo("healthy");
        assertThat(stack.nginxExec("cat", "/etc/nginx/conf.d/sites/extra.local.conf").getStdout())
                .contains("# 메모: 결제 팀 운영");
    }

    @Test
    @DisplayName("접근 제한·비밀번호·속도 제한을 모두 켜도 ACME 인증 경로는 열려 있다")
    void acmeChallengeStaysReachable() throws Exception {
        createStatic("locked.local", "/etc/nginx/html", Map.of("options.allowFrom", "10.0.0.0/8",
                "options.authUsers", "kim:correct-pass-1", "options.rateLimit", "1", "options.rateBurst", "0"));
        apply("locked");
        awaitStatus("locked.local", "403");

        stack.nginxExec("sh", "-c", "mkdir -p /var/lib/nginx-manager/acme-webroot/.well-known/acme-challenge "
                + "&& echo token-body > /var/lib/nginx-manager/acme-webroot/.well-known/acme-challenge/tok123");
        for (int i = 0; i < 5; i++) {
            assertThat(status("locked.local", "http://127.0.0.1/.well-known/acme-challenge/tok123"))
                    .as("request %d", i).isEqualTo("200");
        }
    }

    @Test
    @DisplayName("HTTP/3를 켠 HTTPS 사이트 두 개가 기본 443 quic reuseport 서버와 함께 검증을 통과한다")
    void http3OnTwoHttpsSites() throws Exception {
        List<String> domains = List.of("h3a.example.com", "h3b.example.com");
        for (String domain : domains) {
            createStatic(domain, "/etc/nginx/html", Map.of("httpsEnabled", "true", "redirectHttpToHttps", "true",
                    "certificateId", "", "options.http3", "true", "options.hsts", "true"));
        }
        apply("http3");

        for (String domain : domains) {
            // 인증서를 받으면 관리도구가 HTTPS 설정을 더해 스스로 적용한다
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
                    .until(() -> https(domain).contains("HTTP/2 200"));
            String file = stack.nginxExec("cat", "/etc/nginx/conf.d/sites/" + domain + ".conf").getStdout();
            assertThat(file).contains("listen 443 quic;").doesNotContain("reuseport");
            String headers = https(domain);
            assertThat(headers).containsIgnoringCase("alt-svc: h3=\":443\"")
                    .containsIgnoringCase("strict-transport-security: max-age=31536000");
        }
        ExecResult test = stack.nginxExec("nginx", "-t");
        assertThat(test.getExitCode()).as(test.getStderr()).isZero();
        assertThat(stack.nginxExec("nginx", "-T").getStdout()).contains("listen 443 quic reuseport default_server;");
    }

    @Test
    @DisplayName("폼으로 만든 TCP 스트림이 백엔드로 연결을 넘긴다")
    void tcpStreamForwardsConnections() throws Exception {
        createStatic("behind-stream.local", "/etc/nginx/html", Map.of());
        HttpResponse<String> created = client.post("/streams/new", Map.of("protocol", "TCP", "listenPort", "15080",
                "backends", "127.0.0.1:80", "loadBalancing", "ROUND_ROBIN", "connectTimeout", "5",
                "idleTimeout", "60", "note", "image test"), "/streams/new");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(302);
        apply("tcp stream");

        assertThat(stack.nginxExec("cat", "/etc/nginx/stream.d/tcp-15080.conf").getStdout())
                .contains("listen 15080;").contains("server 127.0.0.1:80;");
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> stack.curlStatus("-H", "Host: behind-stream.local", "http://127.0.0.1:15080/")
                        .equals("200"));
    }

    @Test
    @DisplayName("스트림 접근 제한(025): 허용 주소 밖의 연결은 끊고, 허용 주소는 넘긴다")
    void streamAccessRulesFilterClients() throws Exception {
        createStatic("behind-guarded.local", "/etc/nginx/html", Map.of());
        for (Map.Entry<String, String> stream : Map.of("15081", "10.99.0.0/16", "15082", "127.0.0.1").entrySet()) {
            HttpResponse<String> created = client.post("/streams/new", Map.of("protocol", "TCP",
                    "listenPort", stream.getKey(), "backends", "127.0.0.1:80", "loadBalancing", "ROUND_ROBIN",
                    "allowFrom", stream.getValue()), "/streams/new");
            assertThat(created.statusCode()).as(created.body()).isEqualTo(302);
        }
        apply("stream access");

        assertThat(stack.nginxExec("cat", "/etc/nginx/stream.d/tcp-15081.conf").getStdout())
                .contains("allow 10.99.0.0/16;", "deny all;");
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> stack.curlStatus("-H", "Host: behind-guarded.local", "http://127.0.0.1:15082/")
                        .equals("200"));
        assertThat(stack.curlStatus("-H", "Host: behind-guarded.local", "http://127.0.0.1:15081/"))
                .isNotEqualTo("200");
    }

    @Test
    @DisplayName("백엔드 장애 안내(026): 죽은 백엔드는 502와 안내 문구")
    void backendErrorPageReplacesTheDefault502() throws Exception {
        create("down.local", "PROXY", Map.of("proxyTarget", "http://127.0.0.1:8098",
                "options.backendErrorPage", "true", "options.backendErrorMessage", "잠시 후 다시 오세요"));
        apply("backend error page");

        awaitStatus("down.local", "502");
        String body = stack.nginxExec("curl", "--noproxy", "*", "-s", "-H", "Host: down.local",
                "http://127.0.0.1/").getStdout();
        assertThat(body).contains("잠시 후 다시 오세요").doesNotContain("nginx</center>");
    }

    private static String cacheStatus(String path) throws Exception {
        String headers = stack.nginxExec("curl", "--noproxy", "*", "-s", "-o", "/dev/null", "-D", "-",
                "-H", "Host: cache.local", "http://127.0.0.1" + path).getStdout();
        assertThat(headers).containsIgnoringCase("X-Cache-Status");
        return headers.lines().filter(l -> l.toLowerCase().startsWith("x-cache-status:"))
                .map(l -> l.substring(l.indexOf(':') + 1).strip()).findFirst().orElseThrow();
    }

    private static String https(String domain) throws Exception {
        return stack.nginxExec("curl", "--noproxy", "*", "-k", "-s", "-o", "/dev/null", "-D", "-",
                "--resolve", domain + ":443:127.0.0.1", "https://" + domain + "/").getStdout();
    }

    @Test
    @DisplayName("PHP 사이트(016): 실제 nginx가 받아들이고, 비밀번호 보호는 .php 요청에도 걸린다")
    void phpSiteProtectsPhpFiles() throws Exception {
        create("php.local", "PHP", Map.of("staticRoot", "/etc/nginx/html", "proxyTarget", "127.0.0.1:9999",
                "options.authUsers", "kim:correct-pass-1"));
        apply("php site");

        awaitStatus("php.local", "401");
        assertThat(status("php.local", "http://127.0.0.1/index.php")).isEqualTo("401");
        // 비밀번호가 맞으면 PHP-FPM으로 넘긴다(여기에는 PHP-FPM이 없어 파일이 없다는 404나 연결 실패 502)
        assertThat(status("php.local", "-u", "kim:correct-pass-1", "http://127.0.0.1/index.php")).isIn("404", "502");
    }

    @Test
    @DisplayName("외부 인증(018): 인증 서버가 401이면 로그인 주소로 보내고, 통과하면 내용을 준다")
    void externalAuthRedirectsToSignInAndPassesWhenAllowed() throws Exception {
        // 인증 서버 대신 Basic 인증이 걸린 사이트를 8085 포트에 둔다(이 포트의 유일한 서버라 Host와 관계없이 받는다)
        createStatic("authsrv.local", "/etc/nginx/html", Map.of("listenHttpPort", "8085",
                "options.authUsers", "kim:correct-pass-1"));
        createStatic("secure.local", "/etc/nginx/html", Map.of(
                "options.authRequestUrl", "http://127.0.0.1:8085/",
                "options.authSignInUrl", "https://login.example.com/start"));
        apply("external auth");

        awaitStatus("secure.local", "302");
        assertThat(status("secure.local", "-w", "%{http_code} %{redirect_url}"))
                .isEqualTo("302 https://login.example.com/start?rd=http://secure.local/");
        // 요청 헤더(Authorization)는 인증 서버에도 넘어간다
        assertThat(status("secure.local", "-u", "kim:correct-pass-1", "http://127.0.0.1/")).isEqualTo("200");
    }

    @Test
    @DisplayName("gRPC 프록시(018): 실제 nginx가 grpc_pass와 h2c를 받아들인다")
    void grpcProxyIsAcceptedByNginx() throws Exception {
        // 평문 gRPC(h2c)는 nginx가 그 포트의 기본 서버 설정으로 HTTP/2를 고르므로 전용 포트에 둔다
        create("grpc.local", "PROXY", Map.of("proxyTarget", "grpc://127.0.0.1:50051", "listenHttpPort", "8086"));
        apply("grpc");

        // 백엔드가 없어 502. HTTP/2(사전 지식)로 받는지 본다
        String url = "http://127.0.0.1:8086/helloworld.Greeter/SayHello";
        String result = "";
        for (int i = 0; i < 30 && !result.equals("502 2"); i++) {
            Thread.sleep(1000);
            result = status("grpc.local", "--http2-prior-knowledge", "-w", "%{http_code} %{http_version}", url);
        }
        assertThat(result).isEqualTo("502 2");
    }

    @Test
    @DisplayName("경로별 라우팅(019): /api/는 다른 백엔드로, /files/는 디렉터리로, 나머지는 사이트 내용으로")
    void pathRoutesSendPathsToOtherTargets() throws Exception {
        // 백엔드 대신 Basic 인증이 걸린 사이트를 8087 포트에 둔다. 401이 오면 그 백엔드로 넘어간 것이다
        createStatic("routes-backend.local", "/etc/nginx/html", Map.of("listenHttpPort", "8087",
                "options.authUsers", "kim:correct-pass-1"));
        createStatic("routes.local", "/etc/nginx/html", Map.of(
                "options.pathRoutes", "/api/ http://127.0.0.1:8087/\n/files/ /etc/nginx/html"));
        apply("path routes");

        awaitStatus("routes.local", "200");
        assertThat(status("routes.local", "http://127.0.0.1/api/")).isEqualTo("401");
        assertThat(status("routes.local", "-u", "kim:correct-pass-1", "http://127.0.0.1/api/")).isEqualTo("200");
        assertThat(status("routes.local", "http://127.0.0.1/files/index.html")).isEqualTo("200");
        assertThat(status("routes.local", "http://127.0.0.1/files/missing.html")).isEqualTo("404");
    }

    @Test
    @DisplayName("백업 업스트림(020): 주 업스트림이 죽어 있으면 백업이 답하고, keepalive 설정도 nginx가 받아들인다")
    void backupUpstreamAnswersWhenThePrimaryIsDown() throws Exception {
        // 백업 대신 8088 포트의 정적 사이트를 둔다. 주 업스트림(8099)에는 아무것도 없다
        createStatic("backup-origin.local", "/etc/nginx/html", Map.of("listenHttpPort", "8088"));
        create("failover.local", "PROXY", Map.of("proxyTarget", "http://127.0.0.1:8099",
                "options.backupTargets", "http://127.0.0.1:8088", "options.upstreamMaxFails", "1",
                "options.upstreamFailTimeout", "30", "options.upstreamKeepalive", "8"));
        apply("backup upstream");

        awaitStatus("failover.local", "200");
        assertThat(status("failover.local")).isEqualTo("200");
    }

    @Test
    @DisplayName("점검 모드(021): 방문자는 503과 안내 문구, 허용 주소는 평소대로")
    void maintenanceModeShowsThePageExceptToAllowedAddresses() throws Exception {
        createStatic("closed.local", "/etc/nginx/html", Map.of("options.maintenance", "true",
                "options.maintenanceMessage", "DB 작업 중입니다", "options.maintenanceAllowFrom", "10.99.0.0/16"));
        createStatic("worker.local", "/etc/nginx/html", Map.of("options.maintenance", "true",
                "options.maintenanceAllowFrom", "127.0.0.1"));
        apply("maintenance");

        awaitStatus("closed.local", "503");
        String body = stack.nginxExec("curl", "--noproxy", "*", "-s", "-H", "Host: closed.local",
                "http://127.0.0.1/").getStdout();
        assertThat(body).contains("DB 작업 중입니다");
        assertThat(status("worker.local")).isEqualTo("200");
    }

    @Test
    @DisplayName("사용자 지정 헤더(022): 응답 헤더가 붙고, 백엔드는 바꾼 Host와 더한 헤더를 받는다")
    void customHeadersReachTheClientAndTheBackend() throws Exception {
        // 백엔드(8089)는 받은 요청 헤더를 응답 헤더로 되돌려 준다
        createStatic("echo.local", "/etc/nginx/html", Map.of("listenHttpPort", "8089",
                "options.responseHeaders", "X-Seen-Tenant: $http_x_tenant\nX-Seen-Host: $host"));
        create("headers.local", "PROXY", Map.of("proxyTarget", "http://127.0.0.1:8089",
                "options.requestHeaders", "Host: echo.local\nX-Tenant: acme",
                "options.responseHeaders", "Access-Control-Allow-Origin: *\nContent-Security-Policy: default-src 'self'; img-src *"));
        apply("custom headers");

        awaitStatus("headers.local", "200");
        String headers = stack.nginxExec("curl", "--noproxy", "*", "-s", "-o", "/dev/null", "-D", "-",
                "-H", "Host: headers.local", "http://127.0.0.1/").getStdout();
        assertThat(headers).containsIgnoringCase("Access-Control-Allow-Origin: *")
                .containsIgnoringCase("Content-Security-Policy: default-src 'self'; img-src *")
                .containsIgnoringCase("X-Seen-Tenant: acme")
                .containsIgnoringCase("X-Seen-Host: echo.local");
    }

    @Test
    @DisplayName("대표 도메인으로 모으기(023): 추가 도메인은 경로를 유지해 301, 대표 도메인은 내용")
    void extraDomainsRedirectToThePrimaryDomain() throws Exception {
        createStatic("canon.local", "/etc/nginx/html", Map.of("extraDomains", "www.canon.local",
                "options.redirectToPrimary", "true"));
        apply("primary domain");

        awaitStatus("www.canon.local", "301");
        String headers = stack.nginxExec("curl", "--noproxy", "*", "-s", "-o", "/dev/null", "-D", "-",
                "-H", "Host: www.canon.local", "http://127.0.0.1/a/b?c=1").getStdout();
        assertThat(headers).containsIgnoringCase("Location: http://canon.local/a/b?c=1");
        assertThat(status("canon.local")).isEqualTo("200");
    }

    private static void createStatic(String domain, String root, Map<String, String> fields) throws Exception {
        Map<String, String> all = new LinkedHashMap<>(Map.of("staticRoot", root));
        all.putAll(fields);
        create(domain, "STATIC", all);
    }

    private static void create(String domain, String type, Map<String, String> fields) throws Exception {
        Map<String, String> form = new LinkedHashMap<>(Map.of("primaryDomain", domain, "extraDomains", "",
                "type", type, "listenHttpPort", "80"));
        form.putAll(fields);
        HttpResponse<String> created = client.post("/sites/new", form, "/sites/new");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(302);
    }

    private static void apply(String message) throws Exception {
        String body = client.post("/apply", Map.of("message", message), "/apply").body();
        assertThat(body).as(body).contains("모두 통과했습니다");
    }

    /** reload 직후에는 이전 워커가 잠깐 남아 있을 수 있다. */
    private static void awaitStatus(String host, String expected) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> status(host).equals(expected));
    }

    private static String status(String host, String... args) throws Exception {
        String url = args.length > 0 && args[args.length - 1].startsWith("http") ? null : "http://127.0.0.1/";
        String[] all = new String[args.length + 2 + (url == null ? 0 : 1)];
        all[0] = "-H";
        all[1] = "Host: " + host;
        System.arraycopy(args, 0, all, 2, args.length);
        if (url != null) {
            all[all.length - 1] = url;
        }
        return stack.curlStatus(all);
    }
}
