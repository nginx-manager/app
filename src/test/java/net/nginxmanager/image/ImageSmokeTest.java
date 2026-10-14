package net.nginxmanager.image;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 빌드한 단일 이미지를 띄워 nginx와 관리도구가 함께, 그러나 서로 독립적으로 도는지 확인한다.
 * {@code mvn -Pimage-test test -Dimage.name=nginx-manager/app:dev}
 */
@Tag("image")
class ImageSmokeTest {

    static GenericContainer<?> container;
    static final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeAll
    static void start() {
        container = new GenericContainer<>(DockerImageName.parse(System.getProperty("image.name", "nginx-manager/app:dev")))
                .withExposedPorts(80, 9000)
                .waitingFor(Wait.forHttp("/actuator/health").forPort(9000).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
    }

    @AfterAll
    static void stop() {
        container.stop();
    }

    @Test
    void defaultConfigurationIsValidWithAllModulesLoaded() throws Exception {
        ExecResult test = exec("/command/s6-setuidgid", "nginxm", "nginx", "-t");
        assertThat(test.getExitCode()).as(test.getStderr()).isZero();

        String info = exec("nginx", "-V").getStderr();
        assertThat(info).contains("--with-http_v3_module", "--with-stream", "--with-mail",
                "--with-http_perl_module=dynamic", "njs");
    }

    @Test
    void processesRunAsNonRootUser() throws Exception {
        String processes = exec("ps", "-o", "user=,comm=", "-C", "nginx,java").getStdout();
        assertThat(processes).contains("nginx").contains("java").doesNotContain("root");
    }

    @Test
    void nginxServesTrafficAndManagerIsHealthy() throws Exception {
        assertThat(get(80, "/").statusCode()).isEqualTo(404);
        assertThat(get(9000, "/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get(9000, "/").headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).endsWith("/setup"));
    }

    @Test
    void nginxKeepsServingWhileManagerRestarts() throws Exception {
        exec("pkill", "-f", "app.jar");

        assertThat(get(80, "/").statusCode()).isEqualTo(404);
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2))
                .until(() -> statusOrZero(9000, "/actuator/health") == 200);
    }

    @Test
    void nginxIsRestartedWhenItDies() throws Exception {
        exec("pkill", "-x", "-o", "nginx");

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1))
                .until(() -> statusOrZero(80, "/") == 404);
    }

    private static ExecResult exec(String... command) throws IOException, InterruptedException {
        return container.execInContainer(command);
    }

    private static HttpResponse<Void> get(int port, String path) throws IOException, InterruptedException {
        URI uri = URI.create("http://" + container.getHost() + ":" + container.getMappedPort(port) + path);
        return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private static int statusOrZero(int port, String path) {
        try {
            return get(port, path).statusCode();
        } catch (IOException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }
}
