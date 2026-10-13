package net.nginxmanager.image;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 이미지 안의 실제 nginx에 화면으로 사이트를 만들어 적용하고, 깨진 설정은 적용되지 않는지 확인한다.
 */
@Tag("image")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImageApplyTest {

    static final String PASSWORD = "correct-horse-battery";
    static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    static final Pattern FILE_LINK = Pattern.compile("href=\"/files/(\\d+)\">conf\\.d/sites/app\\.local\\.conf<");
    static final Pattern VERSION = Pattern.compile("name=\"version\" value=\"(\\d+)\"");

    static GenericContainer<?> container;
    static String siteFileId;
    static HttpClient http;

    @BeforeAll
    static void start() throws Exception {
        container = new GenericContainer<>(DockerImageName.parse(System.getProperty("image.name", "nginx-manager/app:dev")))
                .withExposedPorts(80, 9000)
                .waitingFor(Wait.forHttp("/actuator/health").forPort(9000).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).cookieHandler(new CookieManager()).build();

        post("/setup", Map.of("username", "admin", "password", PASSWORD, "passwordConfirm", PASSWORD), "/setup");
        HttpResponse<String> login = post("/login", Map.of("username", "admin", "password", PASSWORD), "/login");
        assertThat(login.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).doesNotContain("error"));
    }

    @AfterAll
    static void stop() {
        container.stop();
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
        assertThat(container.execInContainer("cat", "/etc/nginx/conf.d/sites/app.local.conf").getStdout())
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
        container.execInContainer("sh", "-c", "echo '# edited by hand' >> /etc/nginx/nginx.conf");

        assertThat(get("/versions").body()).contains("서버에서 직접 바뀐 설정").contains("nginx.conf");
        HttpResponse<String> imported = post("/drift/import", Map.of(), "/versions");
        assertThat(imported.statusCode()).isEqualTo(302);

        String versions = get("/versions").body();
        assertThat(versions).contains("외부 변경 가져오기").doesNotContain("id=\"drift\"");
    }

    private static String siteStatus() throws Exception {
        return container.execInContainer("curl", "--noproxy", "*", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                "-H", "Host: app.local", "http://127.0.0.1/").getStdout();
    }

    private static URI uri(String path) {
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(9000) + path);
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** csrfPage를 열어 토큰을 받은 뒤 폼을 보낸다. */
    private static HttpResponse<String> post(String path, Map<String, String> fields, String csrfPage)
            throws IOException, InterruptedException {
        Matcher token = CSRF.matcher(get(csrfPage).body());
        assertThat(token.find()).as("csrf token on " + csrfPage).isTrue();
        Map<String, String> form = new LinkedHashMap<>(fields);
        form.put("_csrf", token.group(1));
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        return http.send(HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
