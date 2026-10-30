package net.nginxmanager.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 시작한 뒤 DB 파일에 빈 페이지가 많으면 줄인다(011). 버전 내용을 합치는 마이그레이션 뒤에 한 번 돈다.
 * PRAGMA와 VACUUM은 JPA로 쓸 수 없어 JDBC로 직접 실행한다.
 */
@Component
public class DatabaseCompaction {

    /** 이만큼 비어 있을 때만 VACUUM 한다. 평소에는 비어 있는 페이지가 거의 없다 */
    static final long THRESHOLD_BYTES = 8L * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(DatabaseCompaction.class);

    private final JdbcTemplate jdbc;

    public DatabaseCompaction(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void compactIfNeeded() {
        try {
            long free = freeBytes();
            if (free >= THRESHOLD_BYTES) {
                log.info("Compacting database ({} MB unused)", free / (1024 * 1024));
                jdbc.execute("VACUUM");
            }
        } catch (RuntimeException e) {
            // 줄이지 못해도 동작에는 문제가 없다
            log.warn("Database compaction skipped: {}", e.getMessage());
        }
    }

    long freeBytes() {
        Long pages = jdbc.queryForObject("PRAGMA freelist_count", Long.class);
        Long size = jdbc.queryForObject("PRAGMA page_size", Long.class);
        return pages == null || size == null ? 0 : pages * size;
    }
}
