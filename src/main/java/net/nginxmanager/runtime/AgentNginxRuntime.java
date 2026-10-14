package net.nginxmanager.runtime;

import net.nginxmanager.runtime.RuntimeStatus.State;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * nginx 컨테이너 안의 에이전트(agent/)를 HTTP로 불러 nginx를 다룬다. 에이전트는 고정된 명령만 실행하며,
 * 요청에 사용자 입력은 들어가지 않는다. 설정 파일 자체는 공유 볼륨으로 주고받는다.
 */
public class AgentNginxRuntime implements NginxRuntime {

    /** 에이전트 API 판. 에이전트(agent/server.go)의 Protocol과 같아야 한다. */
    public static final int PROTOCOL = 1;

    static final String HEADER_PROTOCOL = "X-Nginx-Manager-Protocol";
    static final String HEADER_VERSION = "X-Nginx-Manager-Version";
    static final String HEADER_AGENT_PROTOCOL = "X-Nginx-Agent-Protocol";
    static final String HEADER_AGENT_VERSION = "X-Nginx-Agent-Version";

    private final NginxRuntimeProperties properties;
    private final JsonMapper json;
    private final String appVersion;
    private final HttpClient httpClient;

    private volatile String token;
    private volatile AgentInfo agent = AgentInfo.unknown();

    public AgentNginxRuntime(NginxRuntimeProperties properties, JsonMapper json, String appVersion) {
        this.properties = properties;
        this.json = json;
        this.appVersion = appVersion;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    @Override
    public RuntimeStatus status() {
        JsonNode body;
        try {
            body = call("GET", "/v1/status", properties.commandTimeout());
        } catch (NginxCommandException e) {
            return RuntimeStatus.unreachable();
        }
        JsonNode nginxV = body.path("nginxV");
        String version = null;
        List<NginxModule> modules = List.of();
        if (nginxV.path("exitCode").asInt(-1) == 0) {
            String output = nginxV.path("output").asString("");
            version = NginxOutputParser.parseVersion(output);
            modules = NginxOutputParser.parseModules(output);
        }
        Long pid = pid(body);
        return new RuntimeStatus(pid == null ? State.STOPPED : State.RUNNING, version, modules, pid);
    }

    @Override
    public Long masterPid() {
        return pid(call("GET", "/v1/process", Duration.ofSeconds(5)));
    }

    @Override
    public String bootId() {
        try {
            return call("GET", "/v1/process", Duration.ofSeconds(5)).path("bootId").asString(null);
        } catch (NginxCommandException e) {
            return null;
        }
    }

    @Override
    public ValidationResult validate() {
        JsonNode body = call("POST", "/v1/validate", properties.commandTimeout());
        String output = body.path("output").asString("");
        return new ValidationResult(body.path("exitCode").asInt(-1) == 0, output,
                NginxOutputParser.parseTestOutput(output));
    }

    @Override
    public void reload() {
        call("POST", "/v1/reload?timeout=" + goDuration(properties.healthTimeout()),
                properties.commandTimeout().plus(properties.healthTimeout()));
    }

    @Override
    public HealthResult healthCheck(Duration timeout) {
        JsonNode body;
        try {
            body = call("GET", "/v1/health?timeout=" + goDuration(timeout), properties.commandTimeout().plus(timeout));
        } catch (NginxCommandException e) {
            return HealthResult.failed(e.getMessage());
        }
        return body.path("healthy").asBoolean(false)
                ? HealthResult.ok()
                : HealthResult.failed(body.path("detail").asString("unhealthy"));
    }

    @Override
    public void reopenLogs() {
        call("POST", "/v1/reopen", properties.commandTimeout());
    }

    @Override
    public void start() {
        call("POST", "/v1/start", properties.commandTimeout());
    }

    @Override
    public Optional<StartupCheckReport> startupCheck() {
        JsonNode body;
        try {
            body = call("GET", "/v1/startup-check", Duration.ofSeconds(5));
        } catch (NginxCommandException e) {
            // 아직 결과가 없거나(404) 연결하지 못했다
            return Optional.empty();
        }
        return Optional.of(new StartupCheckReport("ok".equals(body.path("result").asString("")),
                body.path("output").asString(""), instant(body.path("checkedAt").asString(""))));
    }

    @Override
    public AgentInfo agent() {
        return agent;
    }

    private static Long pid(JsonNode body) {
        long pid = body.path("masterPid").asLong(0);
        return pid > 0 ? pid : null;
    }

    /** Go의 time.ParseDuration이 읽는 형식(예: 10000ms). */
    private static String goDuration(Duration duration) {
        return Math.max(0, duration.toMillis()) + "ms";
    }

    private static Instant instant(String value) {
        try {
            return value.isEmpty() ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 에이전트를 부르고 JSON 응답을 돌려준다. 연결하지 못하거나 2xx가 아니면 {@link NginxCommandException}.
     * 토큰이 맞지 않으면(에이전트가 새 토큰을 만든 경우) 토큰 파일을 다시 읽어 한 번 더 보낸다.
     */
    private JsonNode call(String method, String path, Duration timeout) {
        HttpResponse<String> response = send(method, path, timeout, false);
        if (response.statusCode() == 401 && properties.agent().token() == null) {
            response = send(method, path, timeout, true);
        }
        JsonNode body = parse(response.body());
        int status = response.statusCode();
        if (status == 401) {
            throw rejected("에이전트가 토큰을 받아들이지 않았습니다. 두 컨테이너가 같은 토큰을 쓰는지 확인하세요");
        }
        if (status == 409) {
            Integer agentProtocol = body.has("agentProtocol") ? body.path("agentProtocol").asInt() : null;
            agent = new AgentInfo(true, agent.version(), agentProtocol, body.path("error").asString(""));
            throw new NginxCommandException("에이전트와 관리도구의 판이 맞지 않습니다: " + body.path("error").asString(""));
        }
        if (status == 404) {
            throw new NotFound(path);
        }
        if (status / 100 != 2) {
            throw new NginxCommandException(body.path("error").asString("agent returned " + status));
        }
        return body;
    }

    private HttpResponse<String> send(String method, String path, Duration timeout, boolean reloadToken) {
        URI uri = properties.agent().url().resolve(path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout.plusSeconds(5))
                .header("Authorization", "Bearer " + token(reloadToken))
                .header(HEADER_PROTOCOL, Integer.toString(PROTOCOL))
                .header(HEADER_VERSION, appVersion)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            agent = new AgentInfo(response.statusCode() != 401,
                    response.headers().firstValue(HEADER_AGENT_VERSION).orElse(null),
                    response.headers().firstValue(HEADER_AGENT_PROTOCOL).map(AgentNginxRuntime::intOrNull).orElse(null),
                    null);
            return response;
        } catch (IOException e) {
            agent = new AgentInfo(false, agent.version(), agent.protocol(), describe(e));
            throw new NginxCommandException("nginx 에이전트(" + properties.agent().url() + ")에 연결할 수 없습니다: "
                    + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NginxCommandException("interrupted while calling the nginx agent", e);
        }
    }

    private NginxCommandException rejected(String message) {
        agent = new AgentInfo(false, agent.version(), agent.protocol(), message);
        return new NginxCommandException(message);
    }

    private String token(boolean reload) {
        String configured = properties.agent().token();
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        String cached = token;
        if (cached != null && !reload) {
            return cached;
        }
        try {
            cached = Files.readString(properties.agent().tokenFile(), StandardCharsets.UTF_8).strip();
        } catch (NoSuchFileException e) {
            throw rejected("에이전트 토큰 파일(" + properties.agent().tokenFile()
                    + ")이 아직 없습니다. nginx 컨테이너와 같은 볼륨을 쓰는지 확인하세요");
        } catch (IOException e) {
            throw rejected("에이전트 토큰 파일을 읽을 수 없습니다: " + e.getMessage());
        }
        token = cached;
        return cached;
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
        } catch (JacksonException e) {
            return json.createObjectNode().put("error", body.strip());
        }
    }

    private static Integer intOrNull(String value) {
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String describe(IOException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** 에이전트에 그 자원이 없다(예: 시작 검사 결과가 아직 없음). */
    private static final class NotFound extends NginxCommandException {

        NotFound(String path) {
            super("not found: " + path);
        }
    }
}
