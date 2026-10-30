package net.nginxmanager.site;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RawLogsTest {

    static final String LEGACY = """
            server {
                listen 80;
                server_name legacy.example.com;
                access_log /var/log/nginx/legacy/app.log combined;
                # error_log /var/log/nginx/commented.log;

                location /api {
                    access_log /var/log/nginx/api.log;
                    proxy_pass http://10.0.0.5:8080;
                }
            }
            """;

    static final String PLAIN = """
            # 가져온 원문
            server {
                listen 80;
                server_name plain.example.com;
                root /srv/www;
            }

            server {
                listen 443 ssl;
                server_name plain.example.com;
                access_log off;
                root /srv/www;
            }
            """;

    @Test
    void readsTheFirstServerLevelLogPath() {
        assertThat(RawLogs.find(LEGACY, "access_log"))
                .isEqualTo(new RawLogs.Found(RawLogs.Status.FILE, "/var/log/nginx/legacy/app.log"));
        // 주석과 location 안의 로그는 보지 않는다
        assertThat(RawLogs.find(LEGACY, "error_log").status()).isEqualTo(RawLogs.Status.NOT_SET);
    }

    @Test
    void explainsPathsItCannotRead() {
        assertThat(RawLogs.find(PLAIN, "access_log").status()).isEqualTo(RawLogs.Status.OFF);
        assertThat(find("access_log /srv/logs/app.log;")).isEqualTo(new RawLogs.Found(RawLogs.Status.OUTSIDE, "/srv/logs/app.log"));
        assertThat(find("access_log /var/log/nginx/$host.log;").status()).isEqualTo(RawLogs.Status.UNSUPPORTED);
        assertThat(find("access_log syslog:server=10.0.0.1;").status()).isEqualTo(RawLogs.Status.UNSUPPORTED);
        assertThat(find("access_log logs/app.log;").status()).isEqualTo(RawLogs.Status.UNSUPPORTED);
        assertThat(RawLogs.find("server {", "access_log").status()).isEqualTo(RawLogs.Status.UNSUPPORTED);
    }

    @Test
    void aReadableFileWinsOverAnEarlierUnreadableOne() {
        String content = "server {\n    access_log /srv/a.log;\n    access_log /var/log/nginx/sites/x.access.log main;\n}\n";

        assertThat(RawLogs.find(content, "access_log").path()).isEqualTo("/var/log/nginx/sites/x.access.log");
    }

    @Test
    void serversInsideAnHttpBlockAreFound() {
        String content = "http {\n    server {\n        error_log /var/log/nginx/e.log warn;\n    }\n}\n";

        assertThat(RawLogs.find(content, "error_log").path()).isEqualTo("/var/log/nginx/e.log");
    }

    @Test
    void siteLogsAreAddedToEachServerBlockKeepingTheRest() {
        String updated = RawLogs.withSiteLogs(PLAIN, "plain.example.com");

        assertThat(updated).contains("""
                server {
                    # 관리도구 로그 화면에서 볼 수 있게 사이트별 로그로 남긴다
                    access_log /var/log/nginx/sites/plain.example.com.access.log main;
                    error_log /var/log/nginx/sites/plain.example.com.error.log;

                    listen 80;""");
        // access_log off인 443 블록에는 error_log만 더한다
        String https = updated.substring(updated.indexOf("listen 443 ssl;") - 200);
        assertThat(https).contains("error_log /var/log/nginx/sites/plain.example.com.error.log;")
                .doesNotContain("plain.example.com.access.log");
        assertThat(updated).contains("# 가져온 원문").contains("access_log off;").contains("root /srv/www;");
        assertThat(RawLogs.find(updated, "access_log").path()).isEqualTo("/var/log/nginx/sites/plain.example.com.access.log");
        // 이미 더했으면 그대로
        assertThat(RawLogs.withSiteLogs(updated, "plain.example.com")).isEqualTo(updated);
    }

    @Test
    void existingLogDirectivesAreKept() {
        String updated = RawLogs.withSiteLogs(LEGACY, "legacy.example.com");

        assertThat(updated).contains("access_log /var/log/nginx/legacy/app.log combined;")
                .contains("access_log /var/log/nginx/sites/legacy.example.com.access.log main;")
                .contains("access_log /var/log/nginx/api.log;");
    }

    private static RawLogs.Found find(String directive) {
        return RawLogs.find("server {\n    listen 80;\n    " + directive + "\n}\n", "access_log");
    }
}
