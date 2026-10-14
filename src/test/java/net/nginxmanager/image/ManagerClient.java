package net.nginxmanager.image;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 이미지 테스트에서 브라우저처럼 화면을 쓰는 클라이언트(쿠키·CSRF 토큰 처리). */
final class ManagerClient {

    static final String PASSWORD = "correct-horse-battery";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    private final Supplier<String> baseUrl;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .cookieHandler(new CookieManager()).build();

    /** 컨테이너를 다시 시작하면 포트가 바뀌므로 주소를 요청마다 읽는다. */
    ManagerClient(Supplier<String> baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 첫 관리자를 만들고 로그인한다. */
    ManagerClient setupAndLogin() throws IOException, InterruptedException {
        post("/setup", Map.of("username", "admin", "password", PASSWORD, "passwordConfirm", PASSWORD), "/setup");
        return login();
    }

    ManagerClient login() throws IOException, InterruptedException {
        HttpResponse<String> login = post("/login", Map.of("username", "admin", "password", PASSWORD), "/login");
        assertThat(login.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).doesNotContain("error"));
        return this;
    }

    HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** csrfPage를 열어 토큰을 받은 뒤 폼을 보낸다. */
    HttpResponse<String> post(String path, Map<String, String> fields, String csrfPage)
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

    private URI uri(String path) {
        return URI.create(baseUrl.get() + path);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
