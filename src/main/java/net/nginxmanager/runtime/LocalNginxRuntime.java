package net.nginxmanager.runtime;

import net.nginxmanager.runtime.RuntimeStatus.State;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 같은 컨테이너에서 도는 nginx를 다룬다. 실행하는 명령은 아래 상수 인자뿐이며 사용자 입력은 인자로 들어가지 않는다.
 */
public class LocalNginxRuntime implements NginxRuntime {

    private static final Duration HEALTH_RETRY_INTERVAL = Duration.ofMillis(500);

    private final NginxRuntimeProperties properties;
    private final CommandRunner commandRunner;
    private final HttpClient httpClient;

    public LocalNginxRuntime(NginxRuntimeProperties properties, CommandRunner commandRunner) {
        this.properties = properties;
        this.commandRunner = commandRunner;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    @Override
    public RuntimeStatus status() {
        String version = null;
        List<NginxModule> modules = List.of();
        try {
            CommandRunner.Result info = nginx("-V");
            if (info.exitCode() == 0) {
                version = NginxOutputParser.parseVersion(info.output());
                modules = NginxOutputParser.parseModules(info.output());
            }
        } catch (NginxCommandException e) {
            // 실행 파일이 없거나 응답이 없으면 버전을 모르는 상태로 보여 준다
        }
        Long pid = readLiveMasterPid();
        return new RuntimeStatus(pid == null ? State.STOPPED : State.RUNNING, version, modules, pid);
    }

    @Override
    public ValidationResult validate() {
        CommandRunner.Result result = nginx("-t");
        return new ValidationResult(result.exitCode() == 0, result.output(),
                NginxOutputParser.parseTestOutput(result.output()));
    }

    @Override
    public void reload() {
        CommandRunner.Result result = nginx("-s", "reload");
        if (result.exitCode() != 0) {
            throw new NginxCommandException("nginx reload failed: " + result.output().strip());
        }
    }

    @Override
    public void start() {
        CommandRunner.Result result = commandRunner.run(
                List.of(properties.s6Svc().toString(), "-u", properties.s6Service().toString()),
                properties.commandTimeout());
        if (result.exitCode() != 0) {
            throw new NginxCommandException("nginx start failed: " + result.output().strip());
        }
    }

    @Override
    public HealthResult healthCheck(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        HealthResult last;
        do {
            last = checkOnce();
            if (last.healthy()) {
                return last;
            }
            sleep(HEALTH_RETRY_INTERVAL);
        } while (System.nanoTime() < deadline);
        return last;
    }

    private HealthResult checkOnce() {
        if (readLiveMasterPid() == null) {
            return HealthResult.failed("nginx master process is not running");
        }
        HttpRequest request = HttpRequest.newBuilder(properties.statusUrl())
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200
                    ? HealthResult.ok()
                    : HealthResult.failed("status endpoint returned " + response.statusCode());
        } catch (IOException e) {
            return HealthResult.failed("status endpoint unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HealthResult.failed("interrupted");
        }
    }

    private Long readLiveMasterPid() {
        try {
            long pid = Long.parseLong(Files.readString(properties.pidFile()).strip());
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent() ? pid : null;
        } catch (NoSuchFileException | NumberFormatException e) {
            return null;
        } catch (IOException e) {
            throw new NginxCommandException("cannot read pid file " + properties.pidFile(), e);
        }
    }

    private CommandRunner.Result nginx(String... args) {
        List<String> command = new ArrayList<>();
        command.add(properties.binary().toString());
        command.addAll(List.of(args));
        return commandRunner.run(command, properties.commandTimeout());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
