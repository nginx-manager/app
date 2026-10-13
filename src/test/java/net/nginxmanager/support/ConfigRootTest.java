package net.nginxmanager.support;

import net.nginxmanager.audit.AuditLogRepository;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigVersionRepository;
import net.nginxmanager.site.SiteRepository;
import net.nginxmanager.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 임시 설정 루트와 가짜 nginx를 쓰는 통합 테스트의 공통 바탕. 테스트마다 설정 루트와 DB를 비운다.
 */
@IntegrationTest
@Import(FakeRuntimeConfig.class)
public abstract class ConfigRootTest {

    protected static final Path CONFIG_ROOT = createTempDir();

    @DynamicPropertySource
    static void configRoot(DynamicPropertyRegistry registry) {
        registry.add("nginx.runtime.config-root", CONFIG_ROOT::toString);
    }

    @Autowired
    protected FakeNginxRuntime nginx;
    @Autowired
    protected ConfigFileRepository configFileRepository;
    @Autowired
    protected ConfigVersionRepository configVersionRepository;
    @Autowired
    protected SiteRepository siteRepository;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected AuditLogRepository auditLogRepository;

    @BeforeEach
    void resetState() throws IOException {
        nginx.reset();
        siteRepository.deleteAll();
        configFileRepository.deleteAll();
        configVersionRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        try (Stream<Path> paths = Files.walk(CONFIG_ROOT)) {
            paths.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(CONFIG_ROOT)).forEach(p -> p.toFile().delete());
        }
    }

    protected static void writeFile(String relativePath, String content) {
        try {
            Path path = CONFIG_ROOT.resolve(relativePath);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected static String readFile(String relativePath) {
        try {
            Path path = CONFIG_ROOT.resolve(relativePath);
            return Files.exists(path) ? Files.readString(path) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("nm-config-root");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
