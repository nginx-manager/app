package net.nginxmanager.logs;

import net.nginxmanager.runtime.NginxRuntime;
import net.nginxmanager.runtime.NginxRuntimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * nginx 로그가 볼륨을 채우지 않도록, 정해진 크기를 넘은 *.log를 <이름>.1, .2 …로 밀어내고 {@code nginx -s reopen}으로
 * 새 파일을 열게 한다. 오래된 것은 keep 개만 남긴다. 로그 보기(LogCursor)는 파일이 줄어들면 처음부터 다시 읽는다.
 */
@Component
public class LogRotator {

    private static final Logger log = LoggerFactory.getLogger(LogRotator.class);

    private final NginxRuntime runtime;
    private final Path logDir;
    private final long maxBytes;
    private final int keep;

    public LogRotator(NginxRuntime runtime, NginxRuntimeProperties properties,
                      @Value("${nginx-manager.logs.max-size-mb:100}") long maxSizeMb,
                      @Value("${nginx-manager.logs.keep:5}") int keep) {
        this.runtime = runtime;
        this.logDir = properties.logDir();
        this.maxBytes = maxSizeMb * 1024 * 1024;
        this.keep = Math.max(1, keep);
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "${nginx-manager.logs.check-interval:PT1H}")
    public void rotateIfNeeded() {
        try {
            List<Path> rotated = new ArrayList<>();
            for (Path file : logFiles()) {
                if (Files.size(file) >= maxBytes) {
                    shift(file);
                    rotated.add(file);
                }
            }
            if (!rotated.isEmpty()) {
                runtime.reopenLogs();
                log.info("rotated {} log file(s)", rotated.size());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("log rotation failed: {}", e.getMessage());
        }
    }

    private List<Path> logFiles() throws IOException {
        if (!Files.isDirectory(logDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(logDir, 3)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".log"))
                    .toList();
        }
    }

    private void shift(Path file) throws IOException {
        String name = file.getFileName().toString();
        Files.deleteIfExists(file.resolveSibling(name + "." + keep));
        for (int i = keep - 1; i >= 1; i--) {
            Path older = file.resolveSibling(name + "." + i);
            if (Files.exists(older)) {
                Files.move(older, file.resolveSibling(name + "." + (i + 1)), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(file, file.resolveSibling(name + ".1"), StandardCopyOption.REPLACE_EXISTING);
    }
}
