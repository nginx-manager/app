package db.migration;

import net.nginxmanager.config.ConfigFileStore;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 버전 파일 내용을 해시로 한 번만 저장한다(012). 해시(SHA-256)는 SQL로 계산할 수 없어 Java 마이그레이션으로 한다.
 * 내용 행의 생성 시각은 그 내용을 처음 담은 버전 파일의 시각을 쓴다.
 */
public class V11__dedupe_version_content extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE config_content (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        hash       VARCHAR(64) NOT NULL UNIQUE,
                        content    TEXT        NOT NULL,
                        created_at TIMESTAMP   NOT NULL,
                        updated_at TIMESTAMP   NOT NULL,
                        version    BIGINT      NOT NULL DEFAULT 0
                    )""");
            statement.execute("ALTER TABLE config_version_file ADD COLUMN content_id BIGINT REFERENCES config_content (id)");
        }
        // 읽는 중에 같은 테이블을 고치지 않도록 id를 먼저 모은다
        List<Long> fileIds = new ArrayList<>();
        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery("SELECT id FROM config_version_file WHERE content IS NOT NULL ORDER BY id")) {
            while (rows.next()) {
                fileIds.add(rows.getLong(1));
            }
        }
        Map<String, Long> ids = new HashMap<>();
        try (PreparedStatement read = connection.prepareStatement(
                     "SELECT content, created_at FROM config_version_file WHERE id = ?");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO config_content (hash, content, created_at, updated_at, version) VALUES (?, ?, ?, ?, 0)",
                     Statement.RETURN_GENERATED_KEYS);
             PreparedStatement link = connection.prepareStatement(
                     "UPDATE config_version_file SET content_id = ? WHERE id = ?")) {
            for (long fileId : fileIds) {
                String content;
                Object createdAt;
                read.setLong(1, fileId);
                try (ResultSet row = read.executeQuery()) {
                    row.next();
                    content = row.getString(1);
                    createdAt = row.getObject(2);
                }
                String hash = ConfigFileStore.hash(content);
                Long contentId = ids.get(hash);
                if (contentId == null) {
                    insert.setString(1, hash);
                    insert.setString(2, content);
                    insert.setObject(3, createdAt);
                    insert.setObject(4, createdAt);
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        keys.next();
                        contentId = keys.getLong(1);
                    }
                    ids.put(hash, contentId);
                }
                link.setLong(1, contentId);
                link.setLong(2, fileId);
                link.executeUpdate();
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE config_version_file DROP COLUMN content");
            statement.execute("CREATE INDEX idx_config_version_file_content_id ON config_version_file (content_id)");
        }
    }
}
