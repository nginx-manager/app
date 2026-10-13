package net.nginxmanager.runtime;

import net.nginxmanager.runtime.RuntimeStatus.State;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 같은 컨테이너에서 도는 nginx를 다룬다. 실행하는 명령은 아래 상수 인자뿐이며 사용자 입력은 인자로 들어가지 않는다.
 */
public class LocalNginxRuntime implements NginxRuntime {

    private static final Duration HEALTH_RETRY_INTERVAL = Duration.ofMillis(500);
    private static final Duration RELOAD_POLL_INTERVAL = Duration.ofMillis(100);
    private static final int ERROR_LOG_TAIL = 16 * 1024;

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
    public Long masterPid() {
        return readLiveMasterPid();
    }

    @Override
    public ValidationResult validate() {
        CommandRunner.Result result = nginx("-t");
        return new ValidationResult(result.exitCode() == 0, result.output(),
                NginxOutputParser.parseTestOutput(result.output()));
    }

    /**
     * reload 신호를 보내고, 마스터가 새 설정으로 워커를 다시 띄웠는지 확인한다.
     * nginx -t를 통과해도 포트 충돌처럼 실제로 열어 봐야 아는 문제가 있으면 마스터는 이전 설정을 그대로 쓰고
     * 오류만 남기므로, 새 워커가 생기지 않으면 실패로 본다.
     */
    @Override
    public void reload() {
        Long master = readLiveMasterPid();
        Set<Long> workersBefore = master == null ? Set.of() : children(master);
        CommandRunner.Result result = nginx("-s", "reload");
        if (result.exitCode() != 0) {
            throw new NginxCommandException("nginx reload failed: " + result.output().strip());
        }
        if (master == null || workersBefore.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + properties.healthTimeout().toNanos();
        do {
            if (children(master).stream().anyMatch(pid -> !workersBefore.contains(pid))) {
                return;
            }
            sleep(RELOAD_POLL_INTERVAL);
        } while (System.nanoTime() < deadline);
        String cause = lastEmergency();
        throw new NginxCommandException("nginx가 새 설정을 불러오지 못하고 이전 설정을 계속 씁니다"
                + (cause == null ? "." : ": " + cause));
    }

    private static Set<Long> children(long pid) {
        return ProcessHandle.of(pid).stream().flatMap(ProcessHandle::children).map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }

    /** error.log 끝부분에서 마지막 [emerg] 줄. 없거나 읽을 수 없으면 null. */
    private String lastEmergency() {
        try (RandomAccessFile file = new RandomAccessFile(properties.logDir().resolve("error.log").toFile(), "r")) {
            long start = Math.max(0, file.length() - ERROR_LOG_TAIL);
            byte[] tail = new byte[(int) (file.length() - start)];
            file.seek(start);
            file.readFully(tail);
            String found = null;
            for (String line : new String(tail, StandardCharsets.UTF_8).split("\\R")) {
                // 포트 충돌은 재시도 끝에 "still could not bind()"만 남기므로 그 앞의 원인 줄을 쓴다
                if (line.contains("[emerg]") && !line.contains("still could not bind")) {
                    // "[emerg] 92#92: bind() ..." 에서 프로세스 번호를 떼어 낸다
                    found = line.substring(line.indexOf("[emerg]") + "[emerg]".length()).strip()
                            .replaceFirst("^\\d+#\\d+: ", "");
                }
            }
            return found;
        } catch (IOException e) {
            return null;
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
