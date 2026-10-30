package net.nginxmanager.image;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 사이트에 HTTPS를 켜고 적용하면, 사람 손 없이 ACME 서버(Pebble)에서 인증서를 받아 실제 nginx가 443으로 응답하는지 본다.
 */
@Tag("image")
class ImageCertificateTest {

    static final String DOMAIN = "secure.example.com";
    static final String RAW_DOMAIN = "storage.example.com";

    static Network network;
    static GenericContainer<?> pebble;
    static Stack stack;
    static ManagerClient client;

    @BeforeAll
    static void start() throws Exception {
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
    void httpsSiteGetsCertificateAndIsServedOverTls() throws Exception {
        HttpResponse<String> created = client.post("/sites/new", Map.of(
                "primaryDomain", DOMAIN, "extraDomains", "", "type", "STATIC",
                "listenHttpPort", "80", "staticRoot", "/etc/nginx/html",
                "httpsEnabled", "true", "redirectHttpToHttps", "true", "certificateId", ""), "/sites/new");
        assertThat(created.statusCode()).isEqualTo(302);
        assertThat(client.post("/apply", Map.of("message", "https"), "/apply").body()).contains("모두 통과했습니다");

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .until(() -> curl("https://" + DOMAIN + "/").equals("200"));
        // reload 직후에는 이전 워커가 잠깐 남아 있을 수 있다
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> curl("http://" + DOMAIN + "/").equals("301"));
        // curl -v가 보여 주는 서버 인증서 발급자
        String handshake = stack.nginxExec("curl", "--noproxy", "*", "-k", "-s", "-v", "-o", "/dev/null",
                "--resolve", DOMAIN + ":443:127.0.0.1", "https://" + DOMAIN + "/").getStderr();
        assertThat(handshake).containsIgnoringCase("issuer:").containsIgnoringCase("pebble");

        String certs = client.get("/certs").body();
        assertThat(certs).contains(DOMAIN).contains("정상");
        assertThat(client.get("/versions").body()).contains("인증서 발급");
    }

    /** 가져온 원문 사이트(MinIO 프록시처럼 80만 듣는 설정)도 인증서를 받고, 443 블록과 80→443 넘기기를 더할 수 있다. */
    @Test
    void rawSiteGetsCertificateHttpsBlockAndRedirect() throws Exception {
        String raw = """
                server {
                    listen 80;
                    server_name %s;
                    client_max_body_size 0;

                    location / {
                        default_type text/plain;
                        return 200 "minio\\n";
                    }
                }
                """.formatted(RAW_DOMAIN);
        assertThat(client.post("/files/new", Map.of("path", "conf.d/sites/" + RAW_DOMAIN + ".conf", "content", raw),
                "/files/new").statusCode()).isEqualTo(302);
        assertThat(client.post("/apply", Map.of("message", "raw"), "/apply").body()).contains("모두 통과했습니다");

        Matcher option = Pattern.compile("<option value=\"(\\d+)\">" + Pattern.quote(RAW_DOMAIN))
                .matcher(client.get("/certs").body());
        assertThat(option.find()).isTrue();
        String siteId = option.group(1);

        // 인증 경로를 더한 초안 → 적용하면 발급
        client.post("/certs/letsencrypt", Map.of("siteId", siteId, "from", "certs"), "/certs");
        assertThat(client.post("/apply", Map.of("message", "acme"), "/apply").body()).contains("모두 통과했습니다");
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .until(() -> client.get("/sites/" + siteId).body().contains("443 server 블록 초안에 더하기"));

        client.post("/certs/letsencrypt/raw-https", Map.of("siteId", siteId, "redirectHttp", "true"),
                "/sites/" + siteId);
        assertThat(client.post("/apply", Map.of("message", "https"), "/apply").body()).contains("모두 통과했습니다");

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> curl(RAW_DOMAIN, "https://" + RAW_DOMAIN + "/").equals("200"));
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> curl(RAW_DOMAIN, "http://" + RAW_DOMAIN + "/").equals("301"));
        // 갱신에 쓰는 인증 경로는 넘기지 않는다
        assertThat(curl(RAW_DOMAIN, "http://" + RAW_DOMAIN + "/.well-known/acme-challenge/none")).isEqualTo("404");
        String conf = stack.nginxExec("cat", "/etc/nginx/conf.d/sites/" + RAW_DOMAIN + ".conf").getStdout();
        assertThat(conf).contains("listen 443 ssl;", "client_max_body_size 0;", "return 301 https://$host$request_uri;");
    }

    private static String curl(String url) throws Exception {
        return curl(DOMAIN, url);
    }

    private static String curl(String domain, String url) throws Exception {
        return stack.nginxExec("curl", "--noproxy", "*", "-k", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                "--resolve", domain + ":443:127.0.0.1", "--resolve", domain + ":80:127.0.0.1", url).getStdout();
    }
}
