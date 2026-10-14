package net.nginxmanager.image;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 프로세스 감시(quickstart 9)와 모듈 지시어 적용(quickstart 8)을 실제 컨테이너로 확인한다. */
@Tag("image")
class ImageProcessTest {

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
    void streamModuleConfigAppliesThroughRawEditorFlow() throws Exception {
        // 원문 편집기로 만든 파일과 같은 경로(stream.d)에 TCP 프록시를 두고 적용한다
        container.execInContainer("sh", "-c",
                "printf 'server {\\n    listen 127.0.0.1:9999;\\n    proxy_pass 127.0.0.1:80;\\n}\\n' > /etc/nginx/stream.d/tcp.conf"
                        + " && chown nginxm:nginxm /etc/nginx/stream.d/tcp.conf");
        assertThat(client.post("/drift/import", Map.of(), "/versions").statusCode()).isEqualTo(302);
        // 가져온 파일을 그대로 다시 검증·적용해 본다
        assertThat(container.execInContainer("su", "-s", "/bin/sh", "nginxm", "-c", "/usr/sbin/nginx -t").getExitCode())
                .isZero();
        container.execInContainer("su", "-s", "/bin/sh", "nginxm", "-c", "/usr/sbin/nginx -s reload");
        await().atMost(Duration.ofSeconds(10)).until(() -> curl("http://127.0.0.1:9999/").equals("404"));
    }

    @Test
    void nginxKilledIsRestartedAndRecordedManagerKilledDoesNotStopNginx() throws Exception {
        String pid = container.execInContainer("cat", "/run/nginx/nginx.pid").getStdout().strip();
        container.execInContainer("kill", "-9", pid);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
                .until(() -> client.get("/").body().contains("(PID " + pid + " → "));
        await().atMost(Duration.ofSeconds(10)).until(() -> curl("http://127.0.0.1/").equals("404"));
        assertThat(client.get("/audit?action=NGINX_RESTART").body()).contains("다시 시작");

        container.execInContainer("pkill", "-9", "-f", "app.jar");
        assertThat(curl("http://127.0.0.1/")).as("nginx keeps serving while the manager restarts").isEqualTo("404");
        HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        URI login = URI.create("http://" + container.getHost() + ":" + container.getMappedPort(9000) + "/login");
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                .until(() -> http.send(HttpRequest.newBuilder(login).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode() == 200);
    }

    private static String curl(String url) throws Exception {
        return container.execInContainer("curl", "--noproxy", "*", "-s", "-o", "/dev/null", "-w", "%{http_code}", url)
                .getStdout();
    }
}
