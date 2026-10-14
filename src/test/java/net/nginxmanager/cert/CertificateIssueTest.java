package net.nginxmanager.cert;

import net.nginxmanager.apply.ApplyResult;
import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.VersionResult;
import net.nginxmanager.config.VersionSource;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 실제 ACME 서버(Pebble)로 발급과 갱신을 거쳐 HTTPS 설정이 자동으로 적용되는지 본다.
 * Pebble은 챌린지를 모두 통과시키도록 띄운다(이 테스트에는 실제 nginx가 없다).
 */
@Testcontainers(disabledWithoutDocker = true)
class CertificateIssueTest extends ConfigRootTest {

    static final String DOMAIN = "secure.example.com";
    static final String PATH = "conf.d/sites/" + DOMAIN + ".conf";

    @Container
    static final GenericContainer<?> PEBBLE = new GenericContainer<>("ghcr.io/letsencrypt/pebble:2.10.1")
            .withCommand("-config", "/test/config/pebble-config.json", "-strict")
            .withEnv("PEBBLE_VA_ALWAYS_VALID", "1")
            .withEnv("PEBBLE_WFE_NONCEREJECT", "0")
            .withExposedPorts(14000)
            .waitingFor(Wait.forLogMessage(".*ACME directory available.*", 1));

    @DynamicPropertySource
    static void acme(DynamicPropertyRegistry registry) {
        registry.add("nginx-manager.acme.directory-url",
                () -> "acme://pebble/" + PEBBLE.getHost() + ":" + PEBBLE.getMappedPort(14000));
    }

    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;
    @Autowired
    CertificateService certificateService;
    @Autowired
    CertificateFiles certificateFiles;

    @Test
    void enablingHttpsIssuesCertificateAndAppliesItThenRenewalSwapsFiles() throws Exception {
        siteService.create(new SiteForm(DOMAIN, "www." + DOMAIN, SiteType.PROXY, 80, null, "http://127.0.0.1:8080",
                null, true, true, null), "editor");

        ApplyResult applied = applyService.applyPending("editor", "https 켜기");
        assertThat(applied.succeeded()).isTrue();
        // 인증서가 나오기 전에는 HTTP로만 서비스한다
        assertThat(readFile(PATH)).contains("HTTPS: 인증서가 준비되면").doesNotContain("listen 443");

        TlsCertificate cert = await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> certificateRepository.findByName(DOMAIN).orElseThrow(),
                        c -> c.getStatus() == CertificateStatus.ACTIVE);
        assertThat(cert.getLastError()).isNull();
        assertThat(cert.getNextRenewalAt()).isAfter(cert.getNotBefore()).isBefore(cert.getNotAfter());

        String config = readFile(PATH);
        assertThat(config)
                .contains("listen 443 ssl;")
                .contains("ssl_certificate " + cert.getCertPath() + ";")
                .contains("ssl_certificate_key " + cert.getKeyPath() + ";")
                .contains("return 301 https://$host$request_uri;");
        X509Certificate leaf = certificateFiles.readLeaf(cert.paths());
        assertThat(CertificateUploadService.domainsOf(leaf)).containsExactlyInAnyOrder(DOMAIN, "www." + DOMAIN);
        assertThat(lastVersion().getSource()).isEqualTo(VersionSource.CERT_ISSUE);
        assertThat(lastVersion().getResult()).isEqualTo(VersionResult.SUCCESS);
        // 사이트는 폼 사이트로 남는다(원문 사이트로 바뀌지 않는다)
        Site site = siteRepository.findAll().getFirst();
        assertThat(site.getType()).isEqualTo(SiteType.PROXY);
        assertThat(driftDetector.refresh()).isEmpty();

        // 갱신: 새 디렉터리에 받고 설정 경로를 바꿔 적용한다
        assertThat(certificateService.issueOrRenew(cert.getId())).isEmpty();
        TlsCertificate renewed = certificateRepository.findById(cert.getId()).orElseThrow();
        assertThat(renewed.getCertPath()).isNotEqualTo(cert.getCertPath());
        assertThat(readFile(PATH)).contains(renewed.getCertPath()).doesNotContain(cert.getCertPath());
        assertThat(lastVersion().getSource()).isEqualTo(VersionSource.CERT_RENEW);
        // 이전 인증서 파일은 되돌리기에 쓰도록 남겨 둔다
        assertThat(Files.exists(Path.of(cert.getCertPath()))).isTrue();
        assertThat(nginx.reloadCalls).isEqualTo(3);
    }

    private ConfigVersion lastVersion() {
        List<ConfigVersion> versions = configVersionRepository.findAll();
        return versions.stream().max(java.util.Comparator.comparing(ConfigVersion::getId)).orElseThrow();
    }
}
