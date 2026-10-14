package net.nginxmanager.image;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 이미지 안의 실제 nginx에 화면으로 사이트를 만들어 적용하고, 깨진 설정은 적용되지 않는지 확인한다.
 */
@Tag("image")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImageApplyTest {

    static final Pattern FILE_LINK = Pattern.compile("href=\"/files/(\\d+)\">conf\\.d/sites/app\\.local\\.conf<");
    static final Pattern VERSION = Pattern.compile("name=\"version\" value=\"(\\d+)\"");

    static Stack stack;
    static String siteFileId;
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
    @Order(1)
    void siteCreatedInUiIsServedAfterApply() throws Exception {
        HttpResponse<String> created = post("/sites/new", Map.of(
                "primaryDomain", "app.local", "extraDomains", "", "type", "STATIC",
                "listenHttpPort", "80", "staticRoot", "/etc/nginx/html"), "/sites/new");
        assertThat(created.statusCode()).isEqualTo(302);
        assertThat(siteStatus()).as("drafts are not live before apply").isEqualTo("404");

        HttpResponse<String> applied = post("/apply", Map.of("message", "add app.local"), "/apply");
        assertThat(applied.body()).contains("모두 통과했습니다");

        // reload는 신호만 보내고 끝나므로, 새 워커가 뜰 때까지 잠깐 기다린다
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> siteStatus().equals("200"));
    }

    @Test
    @Order(2)
    void brokenEditIsRejectedAndSiteKeepsServing() throws Exception {
        Matcher link = FILE_LINK.matcher(get("/files").body());
        assertThat(link.find()).isTrue();
        String fileId = link.group(1);
        siteFileId = fileId;
        Matcher version = VERSION.matcher(get("/files/" + fileId).body());
        assertThat(version.find()).isTrue();

        post("/files/" + fileId, Map.of("content", "server { listen 80 no_such_flag; }\n",
                "version", version.group(1)), "/files/" + fileId);
        HttpResponse<String> applied = post("/apply", Map.of(), "/apply");

        assertThat(applied.body()).contains("no_such_flag");
        assertThat(stack.nginxExec("cat", "/etc/nginx/conf.d/sites/app.local.conf").getStdout())
                .doesNotContain("no_such_flag");
        assertThat(siteStatus()).isEqualTo("200");
    }

    @Test
    @Order(3)
    void rollingBackToImportedConfigRemovesSite() throws Exception {
        post("/apply/discard/" + siteFileId, Map.of(), "/apply");

        // 버전 #1은 처음 시작할 때 가져온 설정이다
        HttpResponse<String> rollback = post("/versions/1/rollback", Map.of(), "/versions/1");
        assertThat(rollback.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).doesNotContain("rejected").doesNotContain("unchanged"));

        assertThat(get(rollback.headers().firstValue("Location").orElseThrow()
                .replaceFirst("^https?://[^/]+", "")).body()).contains("되돌리기").contains("적용됨");
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> siteStatus().equals("404"));
    }

    @Test
    @Order(4)
    void editsMadeOnTheServerAreDetectedAndImported() throws Exception {
        stack.nginxExec("sh", "-c", "echo '# edited by hand' >> /etc/nginx/nginx.conf");

        assertThat(get("/versions").body()).contains("서버에서 직접 바뀐 설정").contains("nginx.conf");
        HttpResponse<String> imported = post("/drift/import", Map.of(), "/versions");
        assertThat(imported.statusCode()).isEqualTo(302);

        String versions = get("/versions").body();
        assertThat(versions).contains("외부 변경 가져오기").doesNotContain("id=\"drift\"");
    }

    private static String siteStatus() throws Exception {
        return stack.nginxExec("curl", "--noproxy", "*", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                "-H", "Host: app.local", "http://127.0.0.1/").getStdout();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return client.get(path);
    }

    private static HttpResponse<String> post(String path, Map<String, String> fields, String csrfPage) throws Exception {
        return client.post(path, fields, csrfPage);
    }
}
