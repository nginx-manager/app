package net.nginxmanager.cert;

import com.sun.net.httpserver.HttpServer;
import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.config.VersionSource;
import net.nginxmanager.notify.ChannelType;
import net.nginxmanager.notify.NotificationChannel;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.TestCertificates;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CertificateUploadTest extends ConfigRootTest {

    static final String PATH = "conf.d/sites/intra.example.com.conf";

    @Autowired
    CertificateUploadService uploadService;
    @Autowired
    CertificateScheduler scheduler;
    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;
    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;

    @BeforeEach
    void completeSetup() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    @Test
    void uploadedCertificateIsServedByHttpsSiteAndReplacingItReappliesConfig() {
        TestCertificates.Issued first = TestCertificates.selfSigned("*.example.com");
        assertThat(uploadService.upload("wildcard", first.certPem(), first.keyPem(), "editor")).isEmpty();
        TlsCertificate cert = certificateRepository.findByName("wildcard").orElseThrow();
        assertThat(cert.getSource()).isEqualTo(CertificateSource.UPLOADED);
        assertThat(cert.getStatus()).isEqualTo(CertificateStatus.ACTIVE);
        assertThat(cert.domainList()).containsExactly("*.example.com");
        assertThat(cert.isDue(Instant.now())).isFalse();

        siteService.create(new SiteForm("intra.example.com", "", SiteType.PROXY, 80, null, "http://10.0.0.5:8080",
                null, true, false, cert.getId()), "editor");
        assertThat(applyService.applyPending("editor", "https").succeeded()).isTrue();
        assertThat(readFile(PATH))
                .contains("ssl_certificate " + cert.getCertPath() + ";")
                .doesNotContain("return 301")
                .contains("proxy_pass http://10.0.0.5:8080;");

        TestCertificates.Issued second = TestCertificates.selfSigned("*.example.com");
        assertThat(uploadService.upload("wildcard", second.certPem(), second.keyPem(), "editor")).isEmpty();

        TlsCertificate replaced = certificateRepository.findByName("wildcard").orElseThrow();
        assertThat(replaced.getId()).isEqualTo(cert.getId());
        assertThat(replaced.getCertPath()).isNotEqualTo(cert.getCertPath());
        assertThat(readFile(PATH)).contains(replaced.getCertPath()).doesNotContain(cert.getCertPath());
        assertThat(configVersionRepository.findAll()).anyMatch(v -> v.getSource() == VersionSource.CERT_RENEW);
        Site site = siteRepository.findAll().getFirst();
        assertThat(site.getType()).isEqualTo(SiteType.PROXY);
    }

    @Test
    void keyThatDoesNotMatchIsRejected() {
        TestCertificates.Issued cert = TestCertificates.selfSigned("a.example.com");
        TestCertificates.Issued other = TestCertificates.selfSigned("a.example.com");

        assertThatThrownBy(() -> uploadService.upload("a", cert.certPem(), other.keyPem(), "editor"))
                .isInstanceOf(CertificateUploadService.InvalidCertificateException.class)
                .hasMessageContaining("맞지 않습니다");
        assertThat(certificateRepository.count()).isZero();
    }

    @Test
    void expiredCertificateIsRejected() {
        Instant now = Instant.now();
        TestCertificates.Issued old = TestCertificates.selfSigned(List.of("old.example.com"),
                now.minusSeconds(100L * 24 * 3600), now.minusSeconds(3600));

        assertThatThrownBy(() -> uploadService.upload("old", old.certPem(), old.keyPem(), "editor"))
                .isInstanceOf(CertificateUploadService.InvalidCertificateException.class)
                .hasMessageContaining("만료");
    }

    @Test
    void expiringCertificateIsAnnouncedOnceAWeek() throws IOException {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer webhook = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        webhook.createContext("/hook", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        webhook.start();
        try {
            channelRepository.save(new NotificationChannel(ChannelType.WEBHOOK,
                    "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook"));
            Instant now = Instant.now();
            TestCertificates.Issued soon = TestCertificates.selfSigned(List.of("soon.example.com"),
                    now.minusSeconds(80L * 24 * 3600), now.plusSeconds(10L * 24 * 3600));
            uploadService.upload("soon", soon.certPem(), soon.keyPem(), "editor");
            // 어느 사이트도 쓰지 않는 인증서는 만료돼도 알리지 않는다
            TestCertificates.Issued unused = TestCertificates.selfSigned(List.of("unused.example.com"),
                    now.minusSeconds(80L * 24 * 3600), now.plusSeconds(10L * 24 * 3600));
            uploadService.upload("unused", unused.certPem(), unused.keyPem(), "editor");
            Long soonId = certificateRepository.findByName("soon").orElseThrow().getId();
            siteService.create(new SiteForm("soon.example.com", "", SiteType.PROXY, 80, null,
                    "http://10.0.0.5:8080", null, true, false, soonId), "editor");

            scheduler.notifyExpiring();
            scheduler.notifyExpiring();

            assertThat(received).singleElement().satisfies(body -> assertThat(body)
                    .contains("\"event\":\"CERT_EXPIRING\"")
                    .contains("soon")
                    .doesNotContain("unused")
                    .contains("직접 교체"));
        } finally {
            webhook.stop(0);
        }
    }

    @Test
    void uploadThroughWebAndPagesRender() throws Exception {
        TestCertificates.Issued cert = TestCertificates.selfSigned("web.example.com");
        mvc.perform(multipart("/certs/upload").with(csrf()).with(user("ed").roles("EDITOR"))
                        .file(new MockMultipartFile("certificate", "fullchain.pem", "application/x-pem-file",
                                cert.certPem().getBytes(StandardCharsets.US_ASCII)))
                        .file(new MockMultipartFile("privateKey", "privkey.pem", "application/x-pem-file",
                                cert.keyPem().getBytes(StandardCharsets.US_ASCII)))
                        .param("name", "web"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("notice"));

        mvc.perform(get("/certs").with(user("v").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("web.example.com")))
                .andExpect(content().string(containsString("업로드")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("인증서 업로드</h2>"))));

        mvc.perform(multipart("/certs/upload").with(csrf()).with(user("ed").roles("EDITOR"))
                        .file(new MockMultipartFile("certificate", "x", "text/plain", "garbage".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("privateKey", "y", "text/plain", "garbage".getBytes(StandardCharsets.UTF_8)))
                        .param("name", "bad"))
                .andExpect(flash().attributeExists("error"));

        Site site = siteService.create(new SiteForm("web.example.com", "", SiteType.STATIC, 80, "/srv/www", null,
                null, true, true, certificateRepository.findByName("web").orElseThrow().getId()), "ed");
        mvc.perform(get("/sites/" + site.getId()).with(user("ed").roles("EDITOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("HTTPS 인증서")))
                .andExpect(content().string(containsString("web (업로드)")));
        mvc.perform(get("/sites/new").with(user("ed").roles("EDITOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Encrypt 인증서 발급 (무료, 자동 갱신)")));
        mvc.perform(get("/settings/acme").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("스테이징")));
        mvc.perform(get("/settings/notifications").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("채널 추가")));
    }
}
