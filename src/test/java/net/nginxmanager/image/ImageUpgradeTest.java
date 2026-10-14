package net.nginxmanager.image;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 볼륨의 설정을 이 nginx가 받아들이지 않는 상태로 컨테이너를 다시 시작하면(업그레이드 직후와 같다)
 * nginx는 뜨지 않고, 관리 화면이 원인을 보여 주며, 화면에서 고친 뒤 다시 띄울 수 있다(FR-024).
 */
@Tag("image")
class ImageUpgradeTest {

    static final Pattern NGINX_CONF = Pattern.compile("href=\"/files/(\\d+)\">nginx\\.conf<");
    static final Pattern VERSION = Pattern.compile("name=\"version\" value=\"(\\d+)\"");

    static GenericContainer<?> container;
    static volatile String baseUrl;

    @BeforeAll
    static void start() {
        container = new GenericContainer<>(DockerImageName.parse(System.getProperty("image.name", "nginx-manager/app:dev")))
                .withExposedPorts(80, 9000)
                .waitingFor(Wait.forHttp("/actuator/health").forPort(9000).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        baseUrl = "http://" + container.getHost() + ":" + container.getMappedPort(9000);
    }

    @AfterAll
    static void stop() {
        container.stop();
    }

    @Test
    void configRejectedAtStartupKeepsNginxDownUntilFixedInUi() throws Exception {
        ManagerClient client = new ManagerClient(() -> baseUrl).setupAndLogin();
        assertThat(curl()).isEqualTo("404");

        // 새 nginx가 모르는 지시어가 든 설정으로 다시 시작한다
        container.execInContainer("sh", "-c", "echo 'unknown_upgrade_directive on;' >> /etc/nginx/nginx.conf");
        container.getDockerClient().restartContainerCmd(container.getContainerId()).withTimeout(30).exec();
        refreshPort();
        HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                .until(() -> http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/login")).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode() == 200);

        assertThat(curl()).isEqualTo("000");
        client = new ManagerClient(() -> baseUrl).login();
        String dashboard = client.get("/").body();
        assertThat(dashboard).contains("시작 검사 실패").contains("unknown_upgrade_directive")
                .contains("/etc/nginx/nginx.conf:");

        // 원문 편집기에서 고치고 적용한 뒤(nginx가 멈춰 있어 reload 없이 반영) 화면에서 시작한다
        Matcher link = NGINX_CONF.matcher(client.get("/files").body());
        assertThat(link.find()).isTrue();
        String editor = client.get("/files/" + link.group(1)).body();
        Matcher version = VERSION.matcher(editor);
        assertThat(version.find()).isTrue();
        String fixed = container.execInContainer("sh", "-c", "grep -v unknown_upgrade_directive /etc/nginx/nginx.conf")
                .getStdout();
        client.post("/files/" + link.group(1), Map.of("content", fixed, "version", version.group(1)),
                "/files/" + link.group(1));
        assertThat(client.post("/apply", Map.of("message", "업그레이드 뒤 설정 고침"), "/apply").body())
                .contains("reload는 하지 않았습니다");
        client.post("/runtime/start", Map.of(), "/");

        await().atMost(Duration.ofSeconds(15)).until(() -> curl().equals("404"));
        assertThat(client.get("/").body()).doesNotContain("시작 검사 실패").contains("실행 중");
    }

    private static void refreshPort() {
        Ports.Binding[] bindings = container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec()
                .getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(9000));
        baseUrl = "http://" + container.getHost() + ":" + bindings[0].getHostPortSpec();
    }

    private static String curl() throws Exception {
        return container.execInContainer("curl", "--noproxy", "*", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                "http://127.0.0.1/").getStdout();
    }
}
