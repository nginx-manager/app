package net.nginxmanager.cert;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.site.CertificateMode;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.site.WildcardLetsEncryptException;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.TestCertificates;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사이트를 만들 때 HTTPS(Let's Encrypt)는 고를 때만 켜지고, HTTPS 없이 만든 사이트도 나중에 사이트 화면이나
 * 인증서 화면에서 인증서를 받을 수 있는지 본다. ACME 서버 대신 자체 서명 인증서를 돌려주는 가짜를 쓴다.
 */
class OptionalLetsEncryptTest extends ConfigRootTest {

    static final String DOMAIN = "later.example.com";
    static final String PATH = "conf.d/sites/" + DOMAIN + ".conf";

    @TestConfiguration
    static class FakeAcme {
        @Bean
        @Primary
        CountingAcmeClient countingAcmeClient() {
            return new CountingAcmeClient();
        }
    }

    static class CountingAcmeClient implements AcmeClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Issued issue(List<String> domains, java.security.cert.X509Certificate replaces) {
            calls.incrementAndGet();
            TestCertificates.Issued issued = TestCertificates.selfSigned(domains,
                    Instant.now().minusSeconds(60), Instant.now().plusSeconds(90L * 24 * 3600));
            return new Issued(issued.certPem(), issued.keyPem(), issued.certificate(), Optional.empty());
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;
    @Autowired
    CertificateService certificateService;
    @Autowired
    CountingAcmeClient acme;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
        acme.calls.set(0);
    }

    private MockHttpServletRequestBuilder newSite(String mode) {
        return post("/sites/new").with(csrf()).with(user("ed").roles("ADMIN"))
                .param("primaryDomain", DOMAIN).param("type", "PROXY").param("listenHttpPort", "80")
                .param("proxyTarget", "http://10.0.0.1:3000").param("certificateMode", mode)
                // 화면에는 숨어 있어도 함께 보내지는 값들
                .param("redirectHttpToHttps", "true").param("certificateId", "");
    }

    @Test
    void formOffersThreeChoicesAndNoneMeansNoCertificate() throws Exception {
        mvc.perform(get("/sites/new").with(user("ed").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"NONE\"")))
                .andExpect(content().string(containsString("value=\"LETS_ENCRYPT\"")))
                .andExpect(content().string(containsString("value=\"UPLOADED\"")));

        mvc.perform(newSite("NONE")).andExpect(status().is3xxRedirection());

        Site site = siteRepository.findByPrimaryDomain(DOMAIN).orElseThrow();
        assertThat(site.isHttpsEnabled()).isFalse();
        assertThat(site.getCertificateId()).isNull();
        assertThat(certificateRepository.findAll()).isEmpty();
    }

    @Test
    void choosingLetsEncryptCreatesPendingCertificate() throws Exception {
        mvc.perform(newSite("LETS_ENCRYPT")).andExpect(status().is3xxRedirection());

        Site site = siteRepository.findByPrimaryDomain(DOMAIN).orElseThrow();
        assertThat(site.isHttpsEnabled()).isTrue();
        TlsCertificate cert = certificateRepository.findById(site.getCertificateId()).orElseThrow();
        assertThat(cert.getSource()).isEqualTo(CertificateSource.LETS_ENCRYPT);
        assertThat(cert.getStatus()).isEqualTo(CertificateStatus.PENDING);
    }

    @Test
    void uploadedModeNeedsACertificate() throws Exception {
        mvc.perform(newSite("UPLOADED"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("사용할 업로드 인증서를 고르세요.")));
        assertThat(siteRepository.findAll()).isEmpty();
    }

    @Test
    void certificateModeOverridesHiddenFields() {
        SiteForm none = new SiteForm(DOMAIN, "", SiteType.PROXY, 80, null, "http://10.0.0.1:3000", null, true, true,
                7L, false, null, null, null, null, null, null, null, CertificateMode.NONE);
        assertThat(none.httpsEnabled()).isFalse();
        assertThat(none.certificateId()).isNull();

        SiteForm letsEncrypt = new SiteForm(DOMAIN, "", SiteType.PROXY, 80, null, "http://10.0.0.1:3000", null, false,
                true, 7L, false, null, null, null, null, null, null, null, CertificateMode.LETS_ENCRYPT);
        assertThat(letsEncrypt.httpsEnabled()).isTrue();
        assertThat(letsEncrypt.certificateId()).isNull();

        // 코드에서 만든 폼은 httpsEnabled·certificateId로 방식을 정한다
        assertThat(new SiteForm(DOMAIN, "", SiteType.PROXY, 80, null, "http://10.0.0.1:3000", null, true, true, 7L)
                .certificateMode()).isEqualTo(CertificateMode.UPLOADED);
        assertThat(SiteForm.empty().certificateMode()).isEqualTo(CertificateMode.NONE);
    }

    @Test
    void liveSiteGetsCertificateFromSitePageAfterwards() throws Exception {
        mvc.perform(newSite("NONE")).andExpect(status().is3xxRedirection());
        assertThat(applyService.applyPending("ed", "http only").succeeded()).isTrue();
        Site site = siteRepository.findByPrimaryDomain(DOMAIN).orElseThrow();

        mvc.perform(get("/sites/" + site.getId()).with(user("ed").roles("ADMIN")))
                .andExpect(content().string(containsString("Let&#39;s Encrypt 인증서 발급받기")));

        mvc.perform(post("/certs/letsencrypt").with(csrf()).with(user("ed").roles("ADMIN"))
                        .param("siteId", site.getId().toString()))
                .andExpect(redirectedUrl("/sites/" + site.getId() + "#https"))
                .andExpect(flash().attribute("notice", containsString("발급을 시작했습니다")));

        TlsCertificate cert = await().until(() -> certificateRepository.findByName(DOMAIN).orElseThrow(),
                c -> c.getStatus() == CertificateStatus.ACTIVE && !certificateService.isRunning(c.getId()));
        assertThat(acme.calls).hasValue(1);
        assertThat(readFile(PATH)).contains("listen 443 ssl;", "ssl_certificate " + cert.getCertPath() + ";");
        assertThat(siteRepository.findByPrimaryDomain(DOMAIN).orElseThrow().isHttpsEnabled()).isTrue();

        mvc.perform(get("/sites/" + site.getId()).with(user("ed").roles("ADMIN")))
                .andExpect(content().string(containsString("발급됨")))
                .andExpect(content().string(not(containsString("Let&#39;s Encrypt 인증서 발급받기"))));
    }

    @Test
    void unappliedSiteWaitsForApplyThenIssues() throws Exception {
        mvc.perform(newSite("NONE")).andExpect(status().is3xxRedirection());
        Site site = siteRepository.findByPrimaryDomain(DOMAIN).orElseThrow();

        // 인증서 화면에서 고른다. 아직 nginx가 모르는 사이트라 바로 발급하지 않는다
        mvc.perform(get("/certs").with(user("ed").roles("ADMIN")))
                .andExpect(content().string(containsString("<option value=\"" + site.getId() + "\">" + DOMAIN)));
        mvc.perform(post("/certs/letsencrypt").with(csrf()).with(user("ed").roles("ADMIN"))
                        .param("siteId", site.getId().toString()).param("from", "certs"))
                .andExpect(redirectedUrl("/certs"))
                .andExpect(flash().attribute("notice", containsString("적용하면")));
        assertThat(acme.calls).hasValue(0);
        // 초안도 HTTPS를 켠 값으로 바뀌어 있다(인증서가 없으니 아직 HTTP만)
        assertThat(configFileRepository.findByPath(PATH).orElseThrow().getDraftContent())
                .contains("HTTPS: 인증서가 준비되면").doesNotContain("listen 443");

        assertThat(applyService.applyPending("ed", "site").succeeded()).isTrue();
        TlsCertificate cert = await().until(() -> certificateRepository.findByName(DOMAIN).orElseThrow(),
                c -> c.getStatus() == CertificateStatus.ACTIVE && !certificateService.isRunning(c.getId()));
        assertThat(readFile(PATH)).contains("ssl_certificate " + cert.getCertPath() + ";");
    }

    @Test
    void wildcardSitesAreRefused() throws Exception {
        siteService.create(new SiteForm(DOMAIN, "*.later.example.com", SiteType.PROXY, 80, null,
                "http://10.0.0.1:3000", null), "ed");
        Site site = siteRepository.findByPrimaryDomain(DOMAIN).orElseThrow();

        assertThatThrownBy(() -> siteService.enableLetsEncrypt(site.getId(), "ed"))
                .isInstanceOf(WildcardLetsEncryptException.class);
        mvc.perform(post("/certs/letsencrypt").with(csrf()).with(user("ed").roles("ADMIN"))
                        .param("siteId", site.getId().toString()))
                .andExpect(flash().attribute("error", containsString("와일드카드")));
        assertThat(siteRepository.findById(site.getId()).orElseThrow().isHttpsEnabled()).isFalse();
        assertThat(certificateRepository.findAll()).isEmpty();
    }

    @Test
    void liveSiteWithAnUnappliedEditGetsHttpsWithoutThatEdit() throws Exception {
        mvc.perform(newSite("NONE")).andExpect(status().is3xxRedirection());
        assertThat(applyService.applyPending("ed", "http only").succeeded()).isTrue();
        Site site = siteRepository.findByPrimaryDomain(DOMAIN).orElseThrow();
        // 저장만 하고 아직 적용하지 않은 편집
        siteService.update(site.getId(), new SiteForm(DOMAIN, "", SiteType.PROXY, 80, null, "http://10.0.0.2:3000",
                site.getVersion()), "ed");

        mvc.perform(post("/certs/letsencrypt").with(csrf()).with(user("ed").roles("ADMIN"))
                        .param("siteId", site.getId().toString()))
                .andExpect(flash().attribute("notice", containsString("발급을 시작했습니다")));

        TlsCertificate cert = await().until(() -> certificateRepository.findByName(DOMAIN).orElseThrow(),
                c -> c.getStatus() == CertificateStatus.ACTIVE && !certificateService.isRunning(c.getId()));
        // HTTPS는 바로 서비스되고, 적용하지 않은 편집은 여전히 초안에만 있다
        assertThat(readFile(PATH)).contains("listen 443 ssl;", "ssl_certificate " + cert.getCertPath() + ";",
                "10.0.0.1:3000").doesNotContain("10.0.0.2:3000");
        assertThat(configFileRepository.findByPath(PATH).orElseThrow().getDraftContent())
                .contains("10.0.0.2:3000", "ssl_certificate " + cert.getCertPath() + ";");

        assertThat(applyService.applyPending("ed", "edit").succeeded()).isTrue();
        assertThat(readFile(PATH)).contains("listen 443 ssl;", "10.0.0.2:3000");
    }

    @Test
    void rawSitesAreRefusedWithAMessage() throws Exception {
        siteService.create(new SiteForm(DOMAIN, "", SiteType.PROXY, 80, null, "http://10.0.0.1:3000", null), "ed");
        ConfigFile file = configFileRepository.findByPath(PATH).orElseThrow();
        siteService.editRaw(file.getId(), "server { listen 80; server_name " + DOMAIN + "; }\n", file.getVersion(), "ed");
        Site site = siteRepository.findByPrimaryDomain(DOMAIN).orElseThrow();

        mvc.perform(post("/certs/letsencrypt").with(csrf()).with(user("ed").roles("ADMIN"))
                        .param("siteId", site.getId().toString()))
                .andExpect(redirectedUrl("/sites/" + site.getId() + "#https"))
                .andExpect(flash().attribute("error", containsString("원문 편집 사이트")));
        assertThat(certificateRepository.findAll()).isEmpty();

        // 폼을 열어 둔 사이 원문 편집 사이트가 되었으면 폼 값으로 덮어쓰지 않고 이유를 보여 준다
        mvc.perform(post("/sites/" + site.getId()).with(csrf()).with(user("ed").roles("ADMIN"))
                        .param("primaryDomain", DOMAIN).param("type", "PROXY").param("listenHttpPort", "80")
                        .param("proxyTarget", "http://10.0.0.9:3000").param("version", Long.toString(site.getVersion())))
                .andExpect(redirectedUrl("/sites/" + site.getId() + "?rawOnly"));
        mvc.perform(get("/sites/" + site.getId() + "?rawOnly").with(user("ed").roles("ADMIN")))
                .andExpect(content().string(containsString("원문 편집기로 고친 사이트라")));
        assertThat(configFileRepository.findByPath(PATH).orElseThrow().getDraftContent()).doesNotContain("10.0.0.9");
    }
}
