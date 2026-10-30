package net.nginxmanager.user;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 006 FR-601: v1.1의 편집자·조회자는 v1.2에서 멤버가 되고, 기존 사이트에는 담당자가 없다. */
class RoleMigrationTest {

    @Test
    void editorsAndViewersBecomeMembers(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("nm.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, null, null).target("9").load().migrate();
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            for (String[] user : new String[][] {{"root", "ADMIN"}, {"ed", "EDITOR"}, {"vi", "VIEWER"}}) {
                s.executeUpdate("INSERT INTO users (username, password_hash, role, created_at, updated_at) VALUES ('"
                        + user[0] + "', 'x', '" + user[1] + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }

        Flyway.configure().dataSource(url, null, null).load().migrate();

        Map<String, String> roles = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT username, role FROM users ORDER BY id")) {
            while (rs.next()) {
                roles.put(rs.getString(1), rs.getString(2));
            }
        }
        assertThat(roles).containsExactly(Map.entry("root", "ADMIN"), Map.entry("ed", "MEMBER"), Map.entry("vi", "MEMBER"));
    }
}
