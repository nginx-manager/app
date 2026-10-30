package net.nginxmanager.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Properties;

/** {@code nm backup}이 data/manager/backup-status(컨테이너의 /data)에 남긴 결과를 읽는다(015). */
@Component
public class BackupStatusReader {

    private static final Logger log = LoggerFactory.getLogger(BackupStatusReader.class);

    /** nm이 date +%Y-%m-%dT%H:%M:%S%z로 쓴다. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx");

    private final Path file;

    private final String schedule;

    public BackupStatusReader(@Value("${nginx-manager.data-dir}") Path dataDir,
                              @Value("${nginx-manager.backup.at:off}") String at) {
        this.file = dataDir.resolve("backup-status");
        this.schedule = at == null || at.isBlank() || at.equals("off") ? null : at.trim();
    }

    /** nm이 쓰는 상태 파일. */
    public Path statusFile() {
        return file;
    }

    public BackupStatus read() {
        if (!Files.isRegularFile(file)) {
            return new BackupStatus(null, false, null, 0, null, null, schedule);
        }
        Properties values = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(in);
            Instant at = OffsetDateTime.parse(values.getProperty("time", ""), TIME).toInstant();
            return new BackupStatus(at, "ok".equals(values.getProperty("result")), blankToNull(values.getProperty("file")),
                    parseLong(values.getProperty("bytes")), blankToNull(values.getProperty("label")),
                    blankToNull(values.getProperty("reason")), schedule);
        } catch (IOException | DateTimeParseException e) {
            log.warn("백업 상태 파일을 읽지 못했습니다: {}: {}", file, e.getMessage());
            return new BackupStatus(null, false, null, 0, null, null, schedule);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
