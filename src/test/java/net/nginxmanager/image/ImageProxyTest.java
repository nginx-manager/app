package net.nginxmanager.image;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 실제 nginx로 백엔드 여러 개(upstream), 웹소켓, 타임아웃을 켠 프록시 사이트가 검증을 통과하고 요청을 나누는지 확인한다. */
@Tag("image")
class ImageProxyTest {

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
    void proxySpreadsRequestsOverSeveralBackends() throws Exception {
        for (int i = 1; i <= 2; i++) {
            stack.nginxExec("sh", "-c", "mkdir -p /tmp/b" + i + " && echo backend-" + i + " > /tmp/b" + i + "/index.html");
            client.post("/sites/new", Map.of("primaryDomain", "b" + i + ".local", "extraDomains", "", "type", "STATIC",
                    "listenHttpPort", String.valueOf(18080 + i), "staticRoot", "/tmp/b" + i), "/sites/new");
        }
        client.post("/sites/new", Map.of("primaryDomain", "lb.local", "extraDomains", "", "type", "PROXY",
                "listenHttpPort", "80", "proxyTarget", "http://127.0.0.1:18081\nhttp://127.0.0.1:18082",
                "proxyWebsocket", "true", "proxyConnectTimeout", "5", "proxyReadTimeout", "3600",
                "proxyLoadBalancing", "ROUND_ROBIN"), "/sites/new");

        assertThat(client.post("/apply", Map.of("message", "proxy options"), "/apply").body()).contains("모두 통과했습니다");

        await().atMost(Duration.ofSeconds(10)).until(() -> curl().startsWith("backend-"));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            seen.add(curl().strip());
        }
        assertThat(seen).containsExactlyInAnyOrder("backend-1", "backend-2");
    }

    private static String curl() throws Exception {
        return stack.nginxExec("curl", "--noproxy", "*", "-s", "-H", "Host: lb.local", "http://127.0.0.1/")
                .getStdout();
    }
    @Test
    void reloadThatCannotOpenPortIsRolledBack() throws Exception {
        client.post("/sites/new", Map.of("primaryDomain", "port.local", "extraDomains", "", "type", "STATIC",
                "listenHttpPort", "18090", "staticRoot", "/etc/nginx/html"), "/sites/new");
        client.post("/apply", Map.of("message", "port.local"), "/apply");
        await().atMost(Duration.ofSeconds(10)).until(() -> status("port.local", 18090).equals("200"));

        // nginx -t는 포트를 열어 보지 않으므로 통과하지만, 상태 확인용 8081과 겹쳐 reload에서 실패한다
        Matcher link = Pattern.compile("href=\"/files/(\\d+)\">conf\\.d/sites/port\\.local\\.conf<")
                .matcher(client.get("/files").body());
        assertThat(link.find()).isTrue();
        String editor = client.get("/files/" + link.group(1)).body();
        Matcher version = Pattern.compile("name=\"version\" value=\"(\\d+)\"").matcher(editor);
        assertThat(version.find()).isTrue();
        String original = stack.nginxExec("cat", "/etc/nginx/conf.d/sites/port.local.conf").getStdout();
        client.post("/files/" + link.group(1), Map.of("content", original.replace("listen 18090;", "listen 8081;"),
                "version", version.group(1)), "/files/" + link.group(1));

        String result = client.post("/apply", Map.of("message", "port clash"), "/apply").body();

        assertThat(result).contains("직전 설정으로 되돌렸습니다").contains("Address already in use");
        assertThat(stack.nginxExec("cat", "/etc/nginx/conf.d/sites/port.local.conf").getStdout())
                .contains("listen 18090;");
        assertThat(status("port.local", 18090)).isEqualTo("200");
        assertThat(result).doesNotContain("92#92");

        // 실패한 초안은 남아 있으므로 버려서 다른 테스트의 적용에 섞이지 않게 한다
        client.post("/apply/discard/" + link.group(1), Map.of(), "/apply");
    }

    private static String status(String host, int port) throws Exception {
        return stack.nginxExec("curl", "--noproxy", "*", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                "-H", "Host: " + host, "http://127.0.0.1:" + port + "/").getStdout();
    }
}
