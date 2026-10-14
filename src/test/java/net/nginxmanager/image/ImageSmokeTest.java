package net.nginxmanager.image;

import com.github.dockerjava.api.command.TopContainerResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * nginx 컨테이너와 관리도구 컨테이너를 함께 띄워, 서로 독립적으로 돌고 에이전트로만 이어지는지 확인한다.
 * {@code mvn -Pimage-test test -Dimage.name=nginx-manager/app:dev -Dnginx.image.name=nginx-manager/nginx:dev}
 */
@Tag("image")
class ImageSmokeTest {

    static Stack stack;
    static final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeAll
    static void start() {
        stack = Stack.start();
    }

    @AfterAll
    static void stop() {
        stack.close();
    }

    @Test
    void defaultConfigurationIsValidWithAllModulesLoaded() throws Exception {
        ExecResult test = stack.nginxExec("/command/s6-setuidgid", "nginxm", "nginx", "-t");
        assertThat(test.getExitCode()).as(test.getStderr()).isZero();

        String info = stack.nginxExec("nginx", "-V").getStderr();
        assertThat(info).contains("--with-http_v3_module", "--with-stream", "--with-mail",
                "--with-http_perl_module=dynamic", "njs");
    }

    @Test
    void processesRunAsTheSameNonRootUserInBothContainers() {
        List<String> nginxSide = processes(stack.nginx);
        assertThat(nginxSide).anyMatch(p -> p.contains("nginx: master")).anyMatch(p -> p.contains("nginx-agent"));
        assertThat(nginxSide).filteredOn(p -> p.contains("nginx") && !p.contains("s6-"))
                .allMatch(p -> p.startsWith("10101 "));
        assertThat(processes(stack.manager)).filteredOn(p -> p.contains("java")).isNotEmpty()
                .allMatch(p -> p.startsWith("10101 "));
    }

    @Test
    void nginxServesTrafficAndManagerIsHealthy() throws Exception {
        assertThat(get(nginxUrl("/")).statusCode()).isEqualTo(404);
        assertThat(get(stack.managerUrl() + "/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get(stack.managerUrl() + "/").headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).endsWith("/setup"));
    }

    @Test
    void agentRequiresTheSharedToken() throws Exception {
        assertThat(stack.curlStatus("http://127.0.0.1:7070/v1/status")).isEqualTo("401");
        String token = stack.nginxExec("cat", "/var/lib/nginx-manager/agent/token").getStdout().strip();
        assertThat(token).hasSize(64);
        assertThat(stack.curlStatus("-H", "Authorization: Bearer " + token, "http://127.0.0.1:7070/v1/status"))
                .isEqualTo("200");
        // 관리도구 컨테이너에는 nginx도 Docker 소켓도 없다
        assertThat(stack.manager.execInContainer("sh", "-c", "command -v nginx; ls /var/run/docker.sock")
                .getStdout()).isBlank();
    }

    @Test
    void nginxKeepsServingWhileManagerRestarts() throws Exception {
        stack.killAndStartManager();
        assertThat(get(nginxUrl("/")).statusCode()).isEqualTo(404);
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2))
                .until(() -> statusOrZero(stack.managerUrl() + "/actuator/health") == 200);
    }

    @Test
    void nginxIsRestartedWhenItDies() throws Exception {
        stack.nginxExec("pkill", "-x", "-o", "nginx");

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1))
                .until(() -> statusOrZero(nginxUrl("/")) == 404);
    }

    /** docker top의 사용자(UID)와 명령줄. docker top은 pid 열을 꼭 요구하므로 받아서 버린다. */
    private static List<String> processes(GenericContainer<?> container) {
        TopContainerResponse top = DockerClientFactory.instance().client()
                .topContainerCmd(container.getContainerId()).withPsArgs("-o pid,uid,args").exec();
        return Arrays.stream(top.getProcesses())
                .map(row -> String.join(" ", Arrays.copyOfRange(row, 1, row.length))).toList();
    }

    private static String nginxUrl(String path) {
        return "http://" + stack.nginx.getHost() + ":" + stack.nginx.getMappedPort(80) + path;
    }

    private static HttpResponse<Void> get(String url) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private static int statusOrZero(String url) {
        try {
            return get(url).statusCode();
        } catch (IOException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }
}
