package net.nginxmanager.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** v1.2.3 이하에서 쌓인 버전 파일 내용을 해시로 합쳐도 버전마다 내용이 그대로인지 본다(011, SC-1102). */
class VersionContentMigrationTest {

    @TempDir
    Path dir;

    @Test
    void mergesDuplicateContentsAndKeepsEveryVersionFile() throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("nm.db");
        flyway(url, "10").migrate();
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            for (int v = 1; v <= 3; v++) {
                s.execute("INSERT INTO config_version (id, source, result, created_at, updated_at) "
                        + "VALUES (" + v + ", 'APPLY', 'SUCCESS', '2026-10-01 00:00:00', '2026-10-01 00:00:00')");
                file(s, v, "nginx.conf", "'events {}\n'");
                file(s, v, "conf.d/sites/a.conf", "'server { # " + v + " }\n'");
            }
            // 실패한 버전의 지우려던 파일(내용 없음)
            file(s, 3, "conf.d/old.conf", "NULL");
        }

        flyway(url, "latest").migrate();

        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM config_content")) {
                rs.next();
                // nginx.conf 한 개 + a.conf 세 가지
                assertThat(rs.getInt(1)).isEqualTo(4);
            }
            Map<String, String> v2 = new HashMap<>();
            try (ResultSet rs = s.executeQuery("SELECT f.path, c.content, c.hash FROM config_version_file f "
                    + "LEFT JOIN config_content c ON c.id = f.content_id WHERE f.config_version_id = 2")) {
                while (rs.next()) {
                    v2.put(rs.getString(1), rs.getString(2));
                    assertThat(rs.getString(3)).isEqualTo(ConfigFileStore.hash(rs.getString(2)));
                }
            }
            assertThat(v2).containsEntry("nginx.conf", "events {}\n").containsEntry("conf.d/sites/a.conf", "server { # 2 }\n");
            try (ResultSet rs = s.executeQuery("SELECT content_id FROM config_version_file WHERE path = 'conf.d/old.conf'")) {
                rs.next();
                assertThat(rs.getObject(1)).isNull();
            }
        }
    }

    private static void file(Statement s, int version, String path, String content) throws Exception {
        s.execute("INSERT INTO config_version_file (config_version_id, path, content, created_at, updated_at) VALUES ("
                + version + ", '" + path + "', " + content + ", '2026-10-01 00:00:00', '2026-10-01 00:00:00')");
    }

    private static Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").target(target).load();
    }
}
