package net.nginxmanager.cert;

import net.nginxmanager.apply.ApplyResult;
import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.history.RollbackService;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.support.ConfigRootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 옛 인증서 디렉터리는 버전이 가리켜도 정리하고, 그 버전으로 되돌리면 지금 파일로 바꾼다(012). */
class CertificatePruneTest extends ConfigRootTest {

    static final String NAME = "secure.example.com";
    static final String FILE = "conf.d/tls.conf";

    @Autowired
    CertificateFiles certificateFiles;
    @Autowired
    CertificateService certificateService;
    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;
    @Autowired
    RollbackService rollbackService;

    private static Path base() {
        return CONFIG_ROOT.toAbsolutePath().normalize().resolve("certs").resolve(NAME);
    }

    private static CertificatePaths dir(String stamp) throws IOException {
        Path d = base().resolve(stamp);
        Files.createDirectories(d);
        Files.writeString(d.resolve("fullchain.pem"), "cert " + stamp);
        Files.writeString(d.resolve("privkey.pem"), "key " + stamp);
        return new CertificatePaths(d.resolve("fullchain.pem").toString(), d.resolve("privkey.pem").toString());
    }

    private static String tls(CertificatePaths paths) {
        return "ssl_certificate " + paths.certPath() + ";\nssl_certificate_key " + paths.keyPath() + ";\n";
    }

    private long applyFile(String content) {
        if (configFileRepository.findByPath(FILE).isEmpty()) {
            siteService.createFile(FILE, content, "admin");
        } else {
            var file = configFileRepository.findByPath(FILE).orElseThrow();
            siteService.editRaw(file.getId(), content, file.getVersion(), "admin");
        }
        ApplyResult result = applyService.applyPending("admin", null);
        assertThat(result.succeeded()).isTrue();
        return result.versionId();
    }

    private void activate(CertificatePaths paths) {
        TlsCertificate cert = TlsCertificate.letsEncrypt(NAME, List.of(NAME));
        cert.activate(paths, Instant.now().minusSeconds(60), Instant.now().plusSeconds(90L * 24 * 3600),
                Instant.now().plusSeconds(60L * 24 * 3600), Instant.now());
        certificateRepository.save(cert);
    }

    private static List<String> remaining() throws IOException {
        try (Stream<Path> dirs = Files.list(base())) {
            return dirs.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void oldDirectoriesReferencedOnlyByVersionsArePrunedAndRollbackUsesCurrentFiles() throws IOException {
        CertificatePaths first = dir("20260101-000000-000");
        long oldVersion = applyFile(tls(first));
        dir("20260201-000000-000");
        dir("20260301-000000-000");
        dir("20260401-000000-000");
        CertificatePaths current = dir("20260501-000000-000");
        applyFile(tls(current) + "# newer\n");
        activate(current);

        certificateFiles.prune(NAME, current, certificateService::referenced);

        // 지금 것과 바로 이전 두 개만 남는다. 버전이 가리키는 첫 디렉터리도 지운다
        assertThat(remaining()).containsExactly("20260301-000000-000", "20260401-000000-000", "20260501-000000-000");

        ApplyResult rolledBack = rollbackService.rollback(oldVersion, "admin");

        assertThat(rolledBack.succeeded()).isTrue();
        assertThat(readFile(FILE)).isEqualTo(tls(current));
    }

    @Test
    void directoriesUsedByDiskOrDraftsAreKept() throws IOException {
        CertificatePaths onDisk = dir("20260101-000000-000");
        applyFile(tls(onDisk));
        CertificatePaths inDraft = dir("20260201-000000-000");
        var file = configFileRepository.findByPath(FILE).orElseThrow();
        siteService.editRaw(file.getId(), tls(inDraft), file.getVersion(), "admin");
        dir("20260301-000000-000");
        dir("20260401-000000-000");
        dir("20260501-000000-000");
        CertificatePaths current = dir("20260601-000000-000");

        certificateFiles.prune(NAME, current, certificateService::referenced);

        assertThat(remaining()).containsExactly("20260101-000000-000", "20260201-000000-000",
                "20260401-000000-000", "20260501-000000-000", "20260601-000000-000");
    }

    @Test
    void pathsOfACertificateThatNoLongerExistsAreLeftAsTheyAre() throws IOException {
        CertificatePaths first = dir("20260101-000000-000");
        String content = tls(first);
        long version = applyFile(content);
        applyFile("# no tls\n");
        // 인증서를 지워 발급된 것이 없다
        certificateFiles.removeAll(NAME, path -> false);

        rollbackService.rollback(version, "admin");

        // 검증은 가짜 nginx라 통과하지만, 경로는 바뀌지 않는다
        assertThat(readFile(FILE)).isEqualTo(content);
    }
}
