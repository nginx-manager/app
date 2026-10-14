package net.nginxmanager.image;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;

/**
 * docker-compose.yml과 같은 구성으로 nginx 컨테이너와 관리도구 컨테이너를 띄운다.
 * 두 컨테이너는 설정·로그·공유 볼륨을 같은 경로에 두고, 관리도구는 네트워크 별칭 nginx로 에이전트를 찾는다.
 * {@code mvn -Pimage-test test -Dimage.name=nginx-manager/app:dev -Dnginx.image.name=nginx-manager/nginx:dev}
 */
final class Stack implements AutoCloseable {

    static final String MANAGER_IMAGE = System.getProperty("image.name", "nginx-manager/app:dev");
    static final String NGINX_IMAGE = System.getProperty("nginx.image.name", "nginx-manager/nginx:dev");

    private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    final Network network;
    final GenericContainer<?> nginx;
    final GenericContainer<?> manager;
    private final List<String> volumes;
    private final boolean ownsNetwork;

    private Stack(Network network, boolean ownsNetwork, GenericContainer<?> nginx, GenericContainer<?> manager,
                  List<String> volumes) {
        this.network = network;
        this.ownsNetwork = ownsNetwork;
        this.nginx = nginx;
        this.manager = manager;
        this.volumes = volumes;
    }

    static Stack start() {
        return start(Network.newNetwork(), true, manager -> {
        });
    }

    static Stack start(Network network, Consumer<GenericContainer<?>> customizeManager) {
        return start(network, false, customizeManager);
    }

    /** 다른 컨테이너(Pebble 등)와 같은 네트워크에 띄우거나 관리도구 환경 변수를 더할 때. */
    private static Stack start(Network network, boolean ownsNetwork, Consumer<GenericContainer<?>> customizeManager) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String conf = "nm-it-conf-" + id;
        String logs = "nm-it-logs-" + id;
        String shared = "nm-it-shared-" + id;
        String data = "nm-it-data-" + id;
        GenericContainer<?> nginx = new GenericContainer<>(DockerImageName.parse(NGINX_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("nginx")
                .withExposedPorts(80, 443, 7070)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withBinds(
                        Bind.parse(conf + ":/etc/nginx"), Bind.parse(logs + ":/var/log/nginx"),
                        Bind.parse(shared + ":/var/lib/nginx-manager")))
                .waitingFor(Wait.forHttp("/healthz").forPort(7070).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(1)));
        nginx.start();
        GenericContainer<?> manager = new GenericContainer<>(DockerImageName.parse(MANAGER_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("manager")
                .withExposedPorts(9000)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withBinds(
                        Bind.parse(conf + ":/etc/nginx"), Bind.parse(logs + ":/var/log/nginx"),
                        Bind.parse(shared + ":/var/lib/nginx-manager"), Bind.parse(data + ":/data")))
                .waitingFor(Wait.forHttp("/actuator/health").forPort(9000).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        customizeManager.accept(manager);
        manager.start();
        return new Stack(network, ownsNetwork, nginx, manager, List.of(conf, logs, shared, data));
    }

    /** 관리 화면 주소. 컨테이너를 다시 시작하면 호스트 포트가 바뀌므로 매번 읽는다. */
    String managerUrl() {
        return "http://" + manager.getHost() + ":" + hostPort(manager, 9000);
    }

    ManagerClient client() {
        return new ManagerClient(this::managerUrl);
    }

    ExecResult nginxExec(String... command) throws IOException, InterruptedException {
        return nginx.execInContainer(command);
    }

    /** nginx 컨테이너 안에서 curl로 받은 HTTP 상태 코드. 응답이 없으면 000. */
    String curlStatus(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("curl", "--noproxy", "*", "-s", "-o", "/dev/null",
                "-w", "%{http_code}"));
        command.addAll(List.of(args));
        return nginx.execInContainer(command.toArray(String[]::new)).getStdout();
    }

    /** nginx 컨테이너만 다시 시작한다(nginx 이미지를 바꿔 띄우는 것과 같다). 관리도구는 그대로 돈다. */
    void restartNginx() {
        docker().restartContainerCmd(nginx.getContainerId()).withTimeout(30).exec();
        awaitHttp(() -> "http://" + nginx.getHost() + ":" + hostPort(nginx, 7070) + "/healthz");
    }

    /** 관리도구 컨테이너를 강제로 죽였다가 다시 띄운다. */
    void killAndStartManager() {
        killManager();
        startManager();
    }

    void killManager() {
        docker().killContainerCmd(manager.getContainerId()).exec();
    }

    void startManager() {
        docker().startContainerCmd(manager.getContainerId()).exec();
        awaitHttp(() -> managerUrl() + "/actuator/health/liveness");
    }

    private static void awaitHttp(Supplier<String> url) {
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                .until(() -> HTTP.send(HttpRequest.newBuilder(URI.create(url.get())).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode() == 200);
    }

    private static String hostPort(GenericContainer<?> container, int port) {
        Ports.Binding[] bindings = docker().inspectContainerCmd(container.getContainerId()).exec()
                .getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(port));
        return bindings[0].getHostPortSpec();
    }

    private static DockerClient docker() {
        return DockerClientFactory.instance().client();
    }

    @Override
    public void close() {
        manager.stop();
        nginx.stop();
        if (ownsNetwork) {
            network.close();
        }
        for (String volume : volumes) {
            try {
                docker().removeVolumeCmd(volume).exec();
            } catch (RuntimeException e) {
                // 지우지 못한 테스트 볼륨은 다음 docker volume prune에 정리된다
            }
        }
    }
}
