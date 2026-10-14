package net.nginxmanager.image;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 볼륨의 설정을 이 nginx가 받아들이지 않는 상태로 nginx 컨테이너만 다시 시작하면(nginx 이미지 업그레이드와 같다)
 * nginx는 뜨지 않고, 그동안 계속 떠 있던 관리 화면이 원인을 보여 주며, 화면에서 고친 뒤 다시 띄울 수 있다(FR-024).
 */
@Tag("image")
class ImageUpgradeTest {

    static final Pattern NGINX_CONF = Pattern.compile("href=\"/files/(\\d+)\">nginx\\.conf<");
    static final Pattern VERSION = Pattern.compile("name=\"version\" value=\"(\\d+)\"");

    static Stack stack;

    @BeforeAll
    static void start() {
        stack = Stack.start();
    }

    @AfterAll
    static void stop() {
        stack.close();
    }

    @Test
    void configRejectedAtStartupKeepsNginxDownUntilFixedInUi() throws Exception {
        ManagerClient client = stack.client().setupAndLogin();
        assertThat(curl()).isEqualTo("404");

        // 새 nginx가 모르는 지시어가 든 설정으로 nginx 컨테이너만 다시 시작한다. 관리 화면은 그대로 떠 있다
        stack.nginxExec("sh", "-c", "echo 'unknown_upgrade_directive on;' >> /etc/nginx/nginx.conf");
        stack.restartNginx();

        assertThat(curl()).isEqualTo("000");
        // 관리도구는 에이전트가 새로 뜬 것을 알아채고 시작 검사 결과를 다시 읽는다
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1))
                .until(() -> client.get("/").body().contains("시작 검사 실패"));
        String dashboard = client.get("/").body();
        assertThat(dashboard).contains("unknown_upgrade_directive").contains("/etc/nginx/nginx.conf:");

        // 원문 편집기에서 고치고 적용한 뒤(nginx가 멈춰 있어 reload 없이 반영) 화면에서 시작한다
        Matcher link = NGINX_CONF.matcher(client.get("/files").body());
        assertThat(link.find()).isTrue();
        String editor = client.get("/files/" + link.group(1)).body();
        Matcher version = VERSION.matcher(editor);
        assertThat(version.find()).isTrue();
        String fixed = stack.nginxExec("sh", "-c", "grep -v unknown_upgrade_directive /etc/nginx/nginx.conf")
                .getStdout();
        client.post("/files/" + link.group(1), Map.of("content", fixed, "version", version.group(1)),
                "/files/" + link.group(1));
        assertThat(client.post("/apply", Map.of("message", "업그레이드 뒤 설정 고침"), "/apply").body())
                .contains("reload는 하지 않았습니다");
        client.post("/runtime/start", Map.of(), "/");

        await().atMost(Duration.ofSeconds(15)).until(() -> curl().equals("404"));
        assertThat(client.get("/").body()).doesNotContain("시작 검사 실패").contains("실행 중");
    }

    private static String curl() throws Exception {
        return stack.curlStatus("http://127.0.0.1/");
    }
}
