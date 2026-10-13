package net.nginxmanager.image;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 사이트에 HTTPS를 켜고 적용하면, 사람 손 없이 ACME 서버(Pebble)에서 인증서를 받아 실제 nginx가 443으로 응답하는지 본다.
 */
@Tag("image")
class ImageCertificateTest {

    static final String DOMAIN = "secure.example.com";

    static Network network;
    static GenericContainer<?> pebble;
    static GenericContainer<?> container;
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
        container = new GenericContainer<>(DockerImageName.parse(System.getProperty("image.name", "nginx-manager/app:dev")))
                .withNetwork(network)
                .withEnv("NGINX_MANAGER_ACME_DIRECTORY_URL", "acme://pebble/pebble:14000")
                .withExposedPorts(80, 443, 9000)
                .waitingFor(Wait.forHttp("/actuator/health").forPort(9000).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        client = new ManagerClient(container).setupAndLogin();
    }

    @AfterAll
    static void stop() {
        container.stop();
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
        assertThat(curl("http://" + DOMAIN + "/")).isEqualTo("301");
        // curl -v가 보여 주는 서버 인증서 발급자
        String handshake = container.execInContainer("curl", "--noproxy", "*", "-k", "-s", "-v", "-o", "/dev/null",
                "--resolve", DOMAIN + ":443:127.0.0.1", "https://" + DOMAIN + "/").getStderr();
        assertThat(handshake).containsIgnoringCase("issuer:").containsIgnoringCase("pebble");

        String certs = client.get("/certs").body();
        assertThat(certs).contains(DOMAIN).contains("정상");
        assertThat(client.get("/versions").body()).contains("인증서 발급");
    }

    private static String curl(String url) throws Exception {
        return container.execInContainer("curl", "--noproxy", "*", "-k", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                "--resolve", DOMAIN + ":443:127.0.0.1", "--resolve", DOMAIN + ":80:127.0.0.1", url).getStdout();
    }
}
