package net.nginxmanager.image;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 서버를 재부팅하면 업스트림으로 쓰는 다른 컨테이너가 nginx보다 늦게 뜰 수 있다. 시작할 때 업스트림 이름을 아직 못 풀어도
 * nginx는 바로 포기하지 않고 잠시 다시 검사한 뒤 뜬다. 사이트 하나 때문에 모든 사이트가 내려가 있지 않게 한다(034).
 */
@Tag("image")
class ImageStartupResolveTest {

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
    void nginxWaitsForUpstreamNameAtStartupThenStarts() throws Exception {
        assertThat(curl()).isEqualTo("404");
        stack.nginxExec("sh", "-c", "printf '%s\\n' 'server {' '    listen 80;' '    server_name late.test;'"
                + " '    location / { proxy_pass http://late-upstream.internal:8090; }' '}'"
                + " > /etc/nginx/conf.d/sites/late.test.conf");

        // 다시 시작한 컨테이너에는 그 이름이 없다(아직 뜨지 않은 업스트림 컨테이너와 같다)
        stack.restartNginx();
        Thread.sleep(8000);
        assertThat(curl()).isEqualTo("000");

        // 업스트림이 떠서 이름이 풀리면 기다리던 nginx가 뜬다
        stack.nginxExec("sh", "-c", "echo '127.0.0.1 late-upstream.internal' >> /etc/hosts");
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).until(() -> curl().equals("404"));
        assertThat(stack.nginxExec("cat", "/run/nginx-manager/startup-check/result").getStdout().trim())
                .isEqualTo("ok");
    }

    @Test
    void otherConfigErrorsStillKeepNginxDownRightAway() throws Exception {
        // 이름 풀기 말고 다른 오류는 기다려도 고쳐지지 않으므로 지금처럼 바로 내려 둔다(ImageUpgradeTest)
        stack.nginxExec("sh", "-c", "rm -f /etc/nginx/conf.d/sites/late.test.conf"
                + " && echo 'unknown_startup_directive on;' > /etc/nginx/conf.d/sites/bad.test.conf");
        stack.restartNginx();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofSeconds(1))
                .until(() -> stack.nginxExec("/command/s6-svstat", "/run/service/nginx").getStdout()
                        .contains("down"));
        assertThat(curl()).isEqualTo("000");
        stack.nginxExec("rm", "-f", "/etc/nginx/conf.d/sites/bad.test.conf");
        stack.restartNginx();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofSeconds(1)).until(() -> curl().equals("404"));
    }

    private static String curl() throws Exception {
        return stack.curlStatus("http://127.0.0.1/");
    }
}
