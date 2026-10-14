package net.nginxmanager.apply;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 진행 중인 적용의 백업을 데이터 볼륨에 적어 둔다. 적용 도중 관리도구가 죽으면(컨테이너 업데이트, 강제 종료)
 * 검증하지 않은 설정이 공유 볼륨에 남는데, 다음 시작 때 이 기록으로 적용 전 파일로 되돌린다.
 */
@Component
class ApplyJournal {

    /** @param files 적용 전 파일 내용. null이면 파일이 없었다 */
    record Entry(String actor, Instant startedAt, Map<String, String> files) {
    }

    private static final Logger log = LoggerFactory.getLogger(ApplyJournal.class);

    private final Path file;
    private final JsonMapper json;

    ApplyJournal(@Value("${nginx-manager.data-dir}") Path dataDir, JsonMapper json) {
        this.file = dataDir.resolve("apply-journal.json");
        this.json = json;
    }

    void begin(String actor, Map<String, String> backup) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), ".apply-journal-", ".tmp");
            Files.writeString(temp, json.writeValueAsString(new Entry(actor, Instant.now(), backup)));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Optional<Entry> pending() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(Files.readString(file), Entry.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 적용이 끝났다. 지우지 못해도 적용 결과는 그대로 두고 기록만 남긴다(다음 시작 때 되돌리면 drift로 드러난다). */
    void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("could not remove apply journal {}", file, e);
        }
    }
}
