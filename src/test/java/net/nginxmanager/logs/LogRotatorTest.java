package net.nginxmanager.logs;

import net.nginxmanager.runtime.NginxRuntimeProperties;
import net.nginxmanager.support.FakeNginxRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LogRotatorTest {

    @TempDir
    Path logDir;

    private final FakeNginxRuntime runtime = new FakeNginxRuntime();

    private LogRotator rotator(int keep) {
        NginxRuntimeProperties properties = new NginxRuntimeProperties(null, null, null, null, null, null,
                logDir, null, null);
        // 0MB: 비어 있지 않은 파일은 모두 교체 대상
        return new LogRotator(runtime, properties, 0, keep);
    }

    @Test
    void shiftsLargeLogsKeepsLimitAndReopens() throws IOException {
        Path sites = Files.createDirectories(logDir.resolve("sites"));
        Path access = sites.resolve("example.com.access.log");
        rotateWith(access, "first", 2);
        rotateWith(access, "second", 2);
        rotateWith(access, "third", 2);

        assertThat(Files.readString(sites.resolve("example.com.access.log.1"))).isEqualTo("third");
        assertThat(Files.readString(sites.resolve("example.com.access.log.2"))).isEqualTo("second");
        assertThat(sites.resolve("example.com.access.log.3")).doesNotExist();
        assertThat(access).doesNotExist();
        assertThat(runtime.reopenCalls).isEqualTo(3);
    }

    @Test
    void leavesSmallLogsAlone() throws IOException {
        Path error = logDir.resolve("error.log");
        Files.writeString(error, "x", StandardCharsets.UTF_8);
        NginxRuntimeProperties properties = new NginxRuntimeProperties(null, null, null, null, null, null,
                logDir, null, null);
        new LogRotator(runtime, properties, 1, 5).rotateIfNeeded();

        assertThat(error).exists();
        assertThat(runtime.reopenCalls).isZero();
    }

    private void rotateWith(Path file, String content, int keep) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        rotator(keep).rotateIfNeeded();
    }
}
