package net.nginxmanager.image;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 프로세스 감시(quickstart 9)와 모듈 지시어 적용(quickstart 8)을 실제 컨테이너 두 개로 확인한다. */
@Tag("image")
class ImageProcessTest {

    static Stack stack;
    static ManagerClient client;

    @BeforeAll
    static void start() throws Exception {
        stack = Stack.start();
        client = stack.client().setupAndLogin();
    }

    @AfterAll
    static void stop() {
        stack.close();
    }

    @Test
    void streamModuleConfigAppliesThroughRawEditorFlow() throws Exception {
        // 원문 편집기로 만든 파일과 같은 경로(stream.d)에 TCP 프록시를 두고 적용한다
        stack.nginxExec("sh", "-c",
                "printf 'server {\\n    listen 127.0.0.1:9999;\\n    proxy_pass 127.0.0.1:80;\\n}\\n' > /etc/nginx/stream.d/tcp.conf"
                        + " && chown nginxm:nginxm /etc/nginx/stream.d/tcp.conf");
        assertThat(client.post("/drift/import", Map.of(), "/versions").statusCode()).isEqualTo(302);
        // 가져온 파일을 그대로 다시 검증·적용해 본다
        assertThat(stack.nginxExec("su", "-s", "/bin/sh", "nginxm", "-c", "/usr/sbin/nginx -t").getExitCode())
                .isZero();
        stack.nginxExec("su", "-s", "/bin/sh", "nginxm", "-c", "/usr/sbin/nginx -s reload");
        await().atMost(Duration.ofSeconds(10)).until(() -> curl("http://127.0.0.1:9999/").equals("404"));
    }

    @Test
    void nginxKilledIsRestartedAndRecordedManagerKilledDoesNotStopNginx() throws Exception {
        String pid = stack.nginxExec("cat", "/run/nginx/nginx.pid").getStdout().strip();
        stack.nginxExec("kill", "-9", pid);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
                .until(() -> client.get("/").body().contains("(PID " + pid + " → "));
        await().atMost(Duration.ofSeconds(10)).until(() -> curl("http://127.0.0.1/").equals("404"));
        assertThat(client.get("/audit?action=NGINX_RESTART").body()).contains("nginx 재시작");

        // 관리도구 컨테이너가 죽어도 nginx 컨테이너는 계속 서비스한다
        stack.killManager();
        assertThat(curl("http://127.0.0.1/")).as("nginx keeps serving while the manager is down").isEqualTo("404");
        stack.startManager();
        assertThat(curl("http://127.0.0.1/")).isEqualTo("404");
    }

    private static String curl(String url) throws Exception {
        return stack.nginxExec("curl", "--noproxy", "*", "-s", "-o", "/dev/null", "-w", "%{http_code}", url)
                .getStdout();
    }
}
