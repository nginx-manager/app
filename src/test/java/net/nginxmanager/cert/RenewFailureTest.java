package net.nginxmanager.cert;

import com.sun.net.httpserver.HttpServer;
import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditResult;
import net.nginxmanager.notify.ChannelType;
import net.nginxmanager.notify.NotificationChannel;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.FakeNginxRuntime;
import net.nginxmanager.support.TestCertificates;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/** 갱신이 실패해도 지금 인증서로 계속 서비스하고, 실패를 기록하고 알린다. */
@Import(RenewFailureTest.ScriptedAcme.class)
class RenewFailureTest extends ConfigRootTest {

    static final String DOMAIN = "shop.example.com";
    static final String PATH = "conf.d/sites/" + DOMAIN + ".conf";

    @TestConfiguration
    static class ScriptedAcme {
        @Bean
        @Primary
        ScriptedAcmeClient scriptedAcmeClient() {
            return new ScriptedAcmeClient();
        }
    }

    /** 다음 발급 결과를 테스트가 정한다. failure가 있으면 실패한다. */
    static class ScriptedAcmeClient implements AcmeClient {
        volatile AcmeFailure failure;
        volatile X509CertificateHolder lastReplaces = new X509CertificateHolder(null);

        @Override
        public Issued issue(List<String> domains, java.security.cert.X509Certificate replaces) throws AcmeFailure {
            lastReplaces = new X509CertificateHolder(replaces);
            if (failure != null) {
                throw failure;
            }
            TestCertificates.Issued issued = TestCertificates.selfSigned(domains,
                    Instant.now().minusSeconds(60), Instant.now().plusSeconds(90L * 24 * 3600));
            return new Issued(issued.certPem(), issued.keyPem(), issued.certificate(), Optional.empty());
        }
    }

    record X509CertificateHolder(java.security.cert.X509Certificate value) {
    }

    @Autowired
    ScriptedAcmeClient acme;
    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;
    @Autowired
    CertificateService certificateService;
    @Autowired
    CertificateScheduler scheduler;
    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;

    HttpServer webhook;
    final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startWebhook() throws IOException {
        acme.failure = null;
        webhook = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        webhook.createContext("/hook", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        webhook.start();
        channelRepository.save(new NotificationChannel(ChannelType.WEBHOOK,
                "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook"));
    }

    @AfterEach
    void stopWebhook() {
        webhook.stop(0);
    }

    private TlsCertificate issuedSite() {
        siteService.create(new SiteForm(DOMAIN, "", SiteType.STATIC, 80, "/srv/www/shop", null,
                null, true, true, null), "editor");
        // 적용 뒤의 자동 발급은 백그라운드라, 여기서는 직접 끝까지 진행한다
        assertThat(applyService.applyPending("editor", "https").succeeded()).isTrue();
        long id = certificateRepository.findByName(DOMAIN).orElseThrow().getId();
        org.awaitility.Awaitility.await().until(() -> !certificateService.isRunning(id)
                && certificateRepository.findById(id).orElseThrow().getStatus() == CertificateStatus.ACTIVE);
        return certificateRepository.findById(id).orElseThrow();
    }

    @Test
    void failedRenewalKeepsCurrentCertificateRecordsAndNotifies() {
        TlsCertificate before = issuedSite();
        String configBefore = readFile(PATH);
        int reloads = nginx.reloadCalls;
        acme.failure = new AcmeClient.AcmeFailure("발급 한도에 걸렸습니다", Instant.now().plusSeconds(7200), null);

        Optional<String> error = certificateService.issueOrRenew(before.getId());

        assertThat(error).contains("발급 한도에 걸렸습니다");
        // ARI 갱신을 위해 지금 인증서를 알려 준다
        assertThat(acme.lastReplaces.value()).isNotNull();
        TlsCertificate after = certificateRepository.findById(before.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CertificateStatus.ACTIVE);
        assertThat(after.getCertPath()).isEqualTo(before.getCertPath());
        assertThat(after.getFailureCount()).isEqualTo(1);
        assertThat(after.getLastError()).contains("발급 한도");
        // 서버가 알려준 시각(2시간 뒤)이 기본 백오프(1시간)보다 늦으므로 그때까지 다시 시도하지 않는다
        assertThat(after.getRetryAfter()).isAfter(Instant.now().plusSeconds(3600 + 600));
        assertThat(after.isDue(Instant.now())).isFalse();
        assertThat(readFile(PATH)).isEqualTo(configBefore);
        assertThat(nginx.reloadCalls).isEqualTo(reloads);
        assertThat(auditLogRepository.findAll()).anySatisfy(log -> {
            assertThat(log.getAction()).isEqualTo(AuditAction.CERT_RENEW);
            assertThat(log.getResult()).isEqualTo(AuditResult.FAILURE);
        });
        assertThat(received).singleElement().satisfies(body -> assertThat(body)
                .contains("\"event\":\"CERT_RENEW_FAILED\"")
                .contains(DOMAIN));
    }

    @Test
    void newCertificateRejectedByNginxIsDiscardedAndOldOneStays() {
        TlsCertificate before = issuedSite();
        nginx.validation = () -> FakeNginxRuntime.invalid("/etc/nginx/" + PATH, 12, "cannot load certificate");

        Optional<String> error = certificateService.issueOrRenew(before.getId());

        assertThat(error).get().asString().contains("이전 설정으로 되돌렸습니다");
        TlsCertificate after = certificateRepository.findById(before.getId()).orElseThrow();
        assertThat(after.getCertPath()).isEqualTo(before.getCertPath());
        assertThat(after.getFailureCount()).isEqualTo(1);
        assertThat(readFile(PATH)).contains(before.getCertPath());
        // 쓰지 못한 새 인증서 디렉터리는 남기지 않는다
        try (var dirs = Files.list(Path.of(before.getCertPath()).getParent().getParent())) {
            assertThat(dirs.toList()).hasSize(1);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        assertThat(received).singleElement().satisfies(body -> assertThat(body).contains("CERT_RENEW_FAILED"));
    }

    @Test
    void schedulerSkipsCertificatesThatAreNotDueAndWarnsBeforeExpiryOnce() {
        TlsCertificate cert = issuedSite();
        int reloads = nginx.reloadCalls;

        scheduler.renewDue();
        scheduler.notifyExpiring();

        assertThat(nginx.reloadCalls).isEqualTo(reloads);
        assertThat(received).isEmpty();
        assertThat(certificateRepository.findById(cert.getId()).orElseThrow().getLastExpiryNoticeAt()).isNull();
    }

    @Test
    void certificatesOfDisabledSitesAreNotRenewed() {
        TlsCertificate cert = issuedSite();
        var site = siteRepository.findByPrimaryDomain(DOMAIN).orElseThrow();
        siteService.setEnabled(site.getId(), false, site.getVersion(), "editor");
        assertThat(applyService.applyPending("editor", "off").succeeded()).isTrue();
        // 갱신 때가 된 것으로 만든다
        TlsCertificate due = certificateRepository.findById(cert.getId()).orElseThrow();
        due.activate(due.paths(), due.getNotBefore(), due.getNotAfter(), Instant.now().minusSeconds(60), Instant.now());
        certificateRepository.save(due);
        X509CertificateHolder untouched = new X509CertificateHolder(null);
        acme.lastReplaces = untouched;

        scheduler.renewDue();

        // 꺼 둔 사이트의 인증서는 ACME에 요청하지 않는다(013)
        assertThat(acme.lastReplaces).isSameAs(untouched);
        assertThat(certificateRepository.findById(cert.getId()).orElseThrow().getCertPath()).isEqualTo(cert.getCertPath());
    }

    @Test
    void firstIssuanceFailureKeepsTheSiteOnHttpAndShowsWhyAndWhen() throws Exception {
        acme.failure = new AcmeClient.AcmeFailure("발급 한도에 걸렸습니다", Instant.now().plusSeconds(7200), null);
        siteService.create(new SiteForm(DOMAIN, "", SiteType.STATIC, 80, "/srv/www/shop", null,
                null, true, true, null), "editor");

        assertThat(applyService.applyPending("editor", "https").succeeded()).isTrue();
        long id = certificateRepository.findByName(DOMAIN).orElseThrow().getId();
        org.awaitility.Awaitility.await().until(() -> !certificateService.isRunning(id)
                && certificateRepository.findById(id).orElseThrow().getFailureCount() == 1);

        TlsCertificate cert = certificateRepository.findById(id).orElseThrow();
        assertThat(cert.getStatus()).isEqualTo(CertificateStatus.PENDING);
        assertThat(cert.getLastError()).contains("발급 한도");
        // 사이트는 HTTP 그대로 서비스한다
        assertThat(readFile(PATH)).contains("listen 80;").doesNotContain("listen 443");
        org.awaitility.Awaitility.await().untilAsserted(() -> assertThat(received).singleElement()
                .satisfies(body -> assertThat(body).contains("\"event\":\"CERT_ISSUE_FAILED\"").contains(DOMAIN)));

        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
        mvc.perform(get("/").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("발급·갱신 실패")));
        mvc.perform(get("/certs").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("발급 한도에 걸렸습니다")))
                .andExpect(content().string(containsString("이후 다시 시도합니다")));
    }
}
