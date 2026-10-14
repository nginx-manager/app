package net.nginxmanager.image;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 실제 nginx로 대시보드, 사이트 로그 검색, 내려간 nginx 다시 띄우기를 확인한다. */
@Tag("image")
class ImageDashboardTest {

    static final Pattern SITE_ID = Pattern.compile("/sites/(\\d+)");

    static GenericContainer<?> container;
    static ManagerClient client;

    @BeforeAll
    static void start() throws Exception {
        container = new GenericContainer<>(DockerImageName.parse(System.getProperty("image.name", "nginx-manager/app:dev")))
                .withExposedPorts(80, 9000)
                .waitingFor(Wait.forHttp("/actuator/health").forPort(9000).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        client = new ManagerClient(container).setupAndLogin();
    }

    @AfterAll
    static void stop() {
        container.stop();
    }

    @Test
    void dashboardLogsAndManualStart() throws Exception {
        String dashboard = client.get("/").body();
        assertThat(dashboard).contains("실행 중").contains("http_ssl_module").contains("0 활성 / 0 전체");

        var created = client.post("/sites/new", Map.of("primaryDomain", "logs.local", "extraDomains", "",
                "type", "STATIC", "listenHttpPort", "80", "staticRoot", "/etc/nginx/html"), "/sites/new");
        Matcher id = SITE_ID.matcher(created.headers().firstValue("Location").orElseThrow());
        assertThat(id.find()).isTrue();
        client.post("/apply", Map.of("message", "logs"), "/apply");
        await().atMost(Duration.ofSeconds(10)).until(() -> curl("/hello-from-test").equals("404"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                client.get("/logs/" + id.group(1) + "?q=hello-from-test").body()).contains("GET /hello-from-test"));

        // nginx를 내렸다가 화면에서 다시 띄운다
        container.execInContainer("/command/s6-svc", "-d", "/run/service/nginx");
        await().atMost(Duration.ofSeconds(10)).until(() -> client.get("/").body().contains("nginx 시작"));
        var started = client.post("/runtime/start", Map.of(), "/");
        assertThat(started.statusCode()).isEqualTo(302);
        assertThat(client.get("/").body()).contains("nginx가 실행 중입니다").contains("실행 중");
        await().atMost(Duration.ofSeconds(10)).until(() -> curl("/").equals("200"));
    }

    private static String curl(String path) throws Exception {
        return container.execInContainer("curl", "--noproxy", "*", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                "-H", "Host: logs.local", "http://127.0.0.1" + path).getStdout();
    }
}
