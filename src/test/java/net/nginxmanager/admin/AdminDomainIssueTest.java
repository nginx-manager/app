package net.nginxmanager.admin;

import net.nginxmanager.cert.CertificateService;
import net.nginxmanager.cert.CertificateStatus;
import net.nginxmanager.cert.TlsCertificate;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.VersionSource;
import net.nginxmanager.site.DuplicateSiteException;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

/**
 * .env의 관리 도메인으로 관리 화면 프록시 설정을 만들고, 실제 ACME 서버(Pebble)로 받은 인증서를 연결하는지 본다(008 US2).
 */
@Testcontainers(disabledWithoutDocker = true)
class AdminDomainIssueTest extends ConfigRootTest {

    static final String DOMAIN = "nm.example.com";

    @Container
    static final GenericContainer<?> PEBBLE = new GenericContainer<>("ghcr.io/letsencrypt/pebble:2.10.1")
            .withCommand("-config", "/test/config/pebble-config.json", "-strict")
            .withEnv("PEBBLE_VA_ALWAYS_VALID", "1")
            .withEnv("PEBBLE_WFE_NONCEREJECT", "0")
            .withExposedPorts(14000)
            .waitingFor(Wait.forLogMessage(".*ACME directory available.*", 1));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nginx-manager.acme.directory-url",
                () -> "acme://pebble/" + PEBBLE.getHost() + ":" + PEBBLE.getMappedPort(14000));
        registry.add("nginx-manager.admin.domain", () -> DOMAIN);
        registry.add("nginx-manager.admin.email", () -> "ops@example.com");
        // 테스트가 직접 sync()를 부른다
        registry.add("nginx-manager.admin.check-interval", () -> "PT1H");
    }

    @Autowired
    AdminDomainService adminDomain;
    @Autowired
    CertificateService certificateService;
    @Autowired
    SiteService siteService;
    @Autowired
    MockMvc mvc;

    @Test
    void certificateIsIssuedAndTheAdminUiIsServedOverHttpsOnly() throws Exception {
        AdminDomainService.Status first = adminDomain.sync();
        assertThat(first.state()).isEqualTo(AdminDomainService.State.WAITING_CERTIFICATE);
        assertThat(readFile(AdminConfigRenderer.PATH)).contains("return 503").doesNotContain("proxy_pass");
        assertThat(lastVersion().getSource()).isEqualTo(VersionSource.ADMIN_DOMAIN);
        // 관리 화면 설정은 사이트로 잡히지 않는다
        assertThat(siteRepository.count()).isZero();
        assertThat(acmeSettingsRepository.findFirstByOrderByIdAsc().orElseThrow().getContactEmail())
                .isEqualTo("ops@example.com");

        TlsCertificate cert = await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> certificateRepository.findByName(DOMAIN).orElseThrow(),
                        c -> c.getStatus() == CertificateStatus.ACTIVE);

        assertThat(adminDomain.sync().state()).isEqualTo(AdminDomainService.State.ACTIVE);
        assertThat(readFile(AdminConfigRenderer.PATH))
                .contains("return 301 https://$host$request_uri;")
                .contains("ssl_certificate " + cert.getCertPath() + ";")
                .contains("proxy_pass http://manager:9000;");
        assertThat(driftDetector.refresh()).isEmpty();

        // 갱신하면 새 인증서 경로로 바뀐다
        assertThat(certificateService.issueOrRenew(cert.getId())).isEmpty();
        TlsCertificate renewed = certificateRepository.findById(cert.getId()).orElseThrow();
        assertThat(readFile(AdminConfigRenderer.PATH)).contains(renewed.getCertPath()).doesNotContain(cert.getCertPath());
        assertThat(adminDomain.sync().state()).isEqualTo(AdminDomainService.State.ACTIVE);

        mvc.perform(get("/").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("id=\"admin-domain\"")))
                .andExpect(content().string(containsString("https://" + DOMAIN)));
    }

    @Test
    void theAdminDomainCannotBeUsedBySitesOrEditedInTheRawEditor() throws Exception {
        adminDomain.sync();

        assertThatThrownBy(() -> siteService.create(new SiteForm(DOMAIN, "", SiteType.PROXY, 80, null,
                "http://127.0.0.1:8080", null), "admin")).isInstanceOf(DuplicateSiteException.class);
        assertThatThrownBy(() -> siteService.create(new SiteForm("other.example.com", DOMAIN, SiteType.PROXY, 80,
                null, "http://127.0.0.1:8080", null), "admin")).isInstanceOf(DuplicateSiteException.class);

        ConfigFile file = configFileRepository.findByPath(AdminConfigRenderer.PATH).orElseThrow();
        mvc.perform(get("/files/" + file.getId()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("관리 화면 도메인 설정입니다")));
        mvc.perform(post("/files/" + file.getId()).with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("content", "# changed").param("version", String.valueOf(file.getVersion())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/files/" + file.getId() + "/delete").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("version", String.valueOf(file.getVersion())))
                .andExpect(status().isForbidden());
        assertThat(configFileRepository.findByPath(AdminConfigRenderer.PATH).orElseThrow().getDraftContent()).isNull();
    }

    private ConfigVersion lastVersion() {
        return configVersionRepository.findAll().stream().max(Comparator.comparing(ConfigVersion::getId)).orElseThrow();
    }
}
