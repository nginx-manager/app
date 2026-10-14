package net.nginxmanager.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 에이전트 HTTP API를 흉내 낸 서버로 요청·응답 해석을 확인한다. 실제 에이전트와의 연동은 이미지 테스트가 본다. */
class AgentNginxRuntimeTest {

    record Reply(int status, String body) {
    }

    @TempDir
    Path dir;

    HttpServer server;
    final Map<String, Reply> replies = new ConcurrentHashMap<>();
    final List<String> tokens = new CopyOnWriteArrayList<>();
    final List<String> protocols = new CopyOnWriteArrayList<>();
    volatile String acceptedToken = "secret";
    volatile String agentVersion = "1.0.0";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        tokens.add(auth);
        protocols.add(exchange.getRequestHeaders().getFirst("X-Nginx-Manager-Protocol"));
        exchange.getResponseHeaders().add("X-Nginx-Agent-Version", agentVersion);
        exchange.getResponseHeaders().add("X-Nginx-Agent-Protocol", "1");
        Reply reply = ("Bearer " + acceptedToken).equals(auth)
                ? replies.getOrDefault(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath(),
                new Reply(404, "{\"error\":\"not found\"}"))
                : new Reply(401, "{\"error\":\"missing or wrong agent token\"}");
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private AgentNginxRuntime runtime(String token, Path tokenFile) {
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new AgentNginxRuntime(new NginxRuntimeProperties(null, Duration.ofSeconds(2), Duration.ofSeconds(1),
                null, new NginxRuntimeProperties.Agent(url, token, tokenFile)), JsonMapper.builder().build(), "1.0.0");
    }

    @Test
    void statusReadsVersionModulesAndPid() {
        replies.put("GET /v1/status", new Reply(200, """
                {"agentVersion":"1.0.0","protocol":1,"bootId":"b1","masterPid":42,
                 "nginxV":{"exitCode":0,"output":"nginx version: nginx/1.31.6\\nconfigure arguments: --with-http_ssl_module --with-stream=dynamic"}}
                """));

        RuntimeStatus status = runtime("secret", null).status();

        assertThat(status.state()).isEqualTo(RuntimeStatus.State.RUNNING);
        assertThat(status.version()).isEqualTo("1.31.6");
        assertThat(status.masterPid()).isEqualTo(42L);
        assertThat(status.modules()).extracting(NginxModule::name).contains("http_ssl_module");
        assertThat(tokens).containsOnly("Bearer secret");
        assertThat(protocols).containsOnly(Integer.toString(AgentNginxRuntime.PROTOCOL));
    }

    @Test
    void stoppedNginxHasNoPid() {
        replies.put("GET /v1/status", new Reply(200, """
                {"masterPid":0,"nginxV":{"exitCode":0,"output":"nginx version: nginx/1.31.6"}}"""));

        assertThat(runtime("secret", null).status().state()).isEqualTo(RuntimeStatus.State.STOPPED);
    }

    @Test
    void validationFailureIsParsedNotThrown() {
        replies.put("POST /v1/validate", new Reply(200, """
                {"exitCode":1,"output":"nginx: [emerg] unknown directive \\"foo\\" in /etc/nginx/nginx.conf:3\\n"}"""));

        ValidationResult result = runtime("secret", null).validate();

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).singleElement().satisfies(m -> {
            assertThat(m.file()).isEqualTo("/etc/nginx/nginx.conf");
            assertThat(m.line()).isEqualTo(3);
        });
    }

    @Test
    void failedReloadIsAnException() {
        replies.put("POST /v1/reload", new Reply(422, """
                {"error":"nginx가 새 설정을 불러오지 못하고 이전 설정을 계속 씁니다: bind() to 0.0.0.0:80 failed"}"""));

        assertThatThrownBy(() -> runtime("secret", null).reload())
                .isInstanceOf(NginxCommandException.class)
                .hasMessageContaining("bind()");
    }

    @Test
    void healthAndStartupCheckAreRead() {
        replies.put("GET /v1/health", new Reply(200, "{\"healthy\":false,\"detail\":\"nginx master process is not running\"}"));
        replies.put("GET /v1/startup-check", new Reply(200, """
                {"result":"failed","output":"nginx: [emerg] x in /etc/nginx/nginx.conf:1","checkedAt":"2026-10-13T01:00:00Z"}"""));
        AgentNginxRuntime runtime = runtime("secret", null);

        assertThat(runtime.healthCheck(Duration.ZERO).detail()).contains("not running");
        assertThat(runtime.startupCheck()).hasValueSatisfying(check -> {
            assertThat(check.ok()).isFalse();
            assertThat(check.checkedAt()).hasToString("2026-10-13T01:00:00Z");
        });
    }

    @Test
    void missingStartupCheckIsEmpty() {
        assertThat(runtime("secret", null).startupCheck()).isEmpty();
    }

    @Test
    void tokenIsReadFromSharedFileAndReloadedWhenTheAgentMadeANewOne() throws IOException {
        Path tokenFile = dir.resolve("token");
        Files.writeString(tokenFile, "old\n");
        acceptedToken = "old";
        replies.put("GET /v1/process", new Reply(200, "{\"bootId\":\"b1\",\"masterPid\":7}"));
        AgentNginxRuntime runtime = runtime(null, tokenFile);
        assertThat(runtime.masterPid()).isEqualTo(7L);

        // nginx 볼륨을 새로 만들어 에이전트가 토큰을 다시 만들었다
        Files.writeString(tokenFile, "new\n");
        acceptedToken = "new";

        assertThat(runtime.masterPid()).isEqualTo(7L);
        assertThat(tokens).containsExactly("Bearer old", "Bearer old", "Bearer new");
    }

    @Test
    void wrongTokenMarksAgentUnreachable() {
        AgentNginxRuntime runtime = runtime("wrong", null);

        assertThatThrownBy(runtime::masterPid).isInstanceOf(NginxCommandException.class).hasMessageContaining("토큰");
        assertThat(runtime.agent().reachable()).isFalse();
        assertThat(runtime.agent().error()).contains("토큰");
    }

    @Test
    void protocolMismatchIsRecorded() {
        replies.put("GET /v1/process", new Reply(409, "{\"error\":\"nginx-manager protocol 1 is not supported\",\"agentProtocol\":2}"));
        AgentNginxRuntime runtime = runtime("secret", null);

        assertThatThrownBy(runtime::masterPid).isInstanceOf(NginxCommandException.class);
        assertThat(runtime.agent().protocol()).isEqualTo(2);
    }

    @Test
    void agentVersionIsTakenFromEveryResponse() {
        agentVersion = "0.9.0";
        replies.put("GET /v1/process", new Reply(200, "{\"bootId\":\"b1\",\"masterPid\":7}"));
        AgentNginxRuntime runtime = runtime("secret", null);

        assertThat(runtime.bootId()).isEqualTo("b1");
        assertThat(runtime.agent()).isEqualTo(new AgentInfo(true, "0.9.0", 1, null));
    }

    @Test
    void unreachableAgentGivesUnreachableStatus() {
        server.stop(0);
        AgentNginxRuntime runtime = runtime("secret", null);

        assertThat(runtime.status().state()).isEqualTo(RuntimeStatus.State.UNREACHABLE);
        assertThat(runtime.bootId()).isNull();
        assertThat(runtime.agent().reachable()).isFalse();
        assertThat(runtime.agent().error()).isNotBlank();
        assertThatThrownBy(runtime::validate).isInstanceOf(NginxCommandException.class).hasMessageContaining("연결할 수 없습니다");
    }
}
