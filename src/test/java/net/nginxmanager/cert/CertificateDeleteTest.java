package net.nginxmanager.cert;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.site.Site;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

/** 인증서 화면을 발급된 것과 대기·실패로 나눠 보이고, 발급하지 못한 인증서를 지우는지 본다(010). */
class CertificateDeleteTest extends ConfigRootTest {

    static final String ADMIN_DOMAIN = "nm.example.com";
    static final String TYPO = "nm.example.com.net";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nginx-manager.admin.domain", () -> ADMIN_DOMAIN);
        registry.add("nginx-manager.admin.check-interval", () -> "PT1H");
        registry.add("nginx-manager.admin.sync-on-start", () -> "false");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    private TlsCertificate failed(String name) {
        TlsCertificate cert = TlsCertificate.letsEncrypt(name, List.of(name));
        cert.recordFailure("도메인 확인 실패(" + name + "): INVALID", Instant.now(), null);
        return certificateRepository.save(cert);
    }

    private TlsCertificate issued(String name) {
        TlsCertificate cert = TlsCertificate.letsEncrypt(name, List.of(name));
        cert.activate(new CertificatePaths("/x/fullchain.pem", "/x/privkey.pem"), Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(90L * 24 * 3600), Instant.now().plusSeconds(60L * 24 * 3600), Instant.now());
        return certificateRepository.save(cert);
    }

    @Test
    void tabsSeparateIssuedFromPending() throws Exception {
        issued("ok.example.com");
        TlsCertificate typo = failed(TYPO);

        mvc.perform(get("/certs").with(user("ed").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("발급된 인증서")))
                .andExpect(content().string(containsString("발급 대기·실패")))
                .andExpect(content().string(containsString("ok.example.com")))
                .andExpect(content().string(not(containsString(TYPO))));

        mvc.perform(get("/certs").param("tab", "pending").with(user("ed").roles("ADMIN")))
                .andExpect(content().string(containsString(TYPO)))
                .andExpect(content().string(not(containsString("ok.example.com"))))
                .andExpect(content().string(containsString("/certs/" + typo.getId() + "/delete")))
                .andExpect(content().string(containsString("data-confirm=\"인증서 " + TYPO + "을(를) 지울까요?")));
    }

    @Test
    void onlyPendingCertificatesOpenThePendingTabByDefault() throws Exception {
        failed(TYPO);
        mvc.perform(get("/certs").with(user("ed").roles("ADMIN")))
                .andExpect(content().string(containsString(TYPO)))
                .andExpect(content().string(containsString("다시 시도")));
    }

    @Test
    void deletesFailedCertificateAndLeftoverFiles() throws Exception {
        TlsCertificate typo = failed(TYPO);
        Path leftover = CONFIG_ROOT.resolve("certs").resolve(TYPO).resolve("20261030-000000-000");
        Files.createDirectories(leftover);
        Files.writeString(leftover.resolve("fullchain.pem"), "partial");

        mvc.perform(post("/certs/" + typo.getId() + "/delete").with(csrf()).with(user("ed").roles("ADMIN")))
                .andExpect(redirectedUrl("/certs?tab=pending"))
                .andExpect(flash().attribute("notice", containsString("지웠습니다")));

        assertThat(certificateRepository.findAll()).isEmpty();
        assertThat(CONFIG_ROOT.resolve("certs").resolve(TYPO)).doesNotExist();
        assertThat(auditLogRepository.findAll())
                .anySatisfy(l -> assertThat(l.getAction()).isEqualTo(AuditAction.CERT_DELETE));
    }

    @Test
    void certificateUsedBySiteIsNotDeleted() throws Exception {
        mvc.perform(post("/sites/new").with(csrf()).with(user("ed").roles("ADMIN"))
                        .param("primaryDomain", "shop.example.com").param("type", "PROXY").param("listenHttpPort", "80")
                        .param("proxyTarget", "http://10.0.0.1:3000").param("certificateMode", "LETS_ENCRYPT")
                        .param("redirectHttpToHttps", "true").param("certificateId", ""))
                .andExpect(status().is3xxRedirection());
        Site site = siteRepository.findByPrimaryDomain("shop.example.com").orElseThrow();
        long id = site.getCertificateId();

        mvc.perform(get("/certs").param("tab", "pending").with(user("ed").roles("ADMIN")))
                .andExpect(content().string(containsString("사이트(shop.example.com)가 쓰고 있어 지울 수 없습니다")))
                .andExpect(content().string(not(containsString("/certs/" + id + "/delete"))));
        mvc.perform(post("/certs/" + id + "/delete").with(csrf()).with(user("ed").roles("ADMIN")))
                .andExpect(flash().attribute("error", containsString("쓰고 있어")));
        assertThat(certificateRepository.existsById(id)).isTrue();
    }

    @Test
    void adminDomainAndIssuedCertificatesAreNotDeleted() throws Exception {
        TlsCertificate admin = failed(ADMIN_DOMAIN);
        TlsCertificate ok = issued("ok.example.com");

        mvc.perform(post("/certs/" + admin.getId() + "/delete").with(csrf()).with(user("ed").roles("ADMIN")))
                .andExpect(flash().attribute("error", containsString("관리 화면 도메인")));
        mvc.perform(post("/certs/" + ok.getId() + "/delete").with(csrf()).with(user("ed").roles("ADMIN")))
                .andExpect(flash().attribute("error", containsString("발급된 인증서는 지울 수 없습니다")));
        assertThat(certificateRepository.count()).isEqualTo(2);
    }

    @Test
    void membersCannotDelete() throws Exception {
        TlsCertificate typo = failed(TYPO);
        mvc.perform(post("/certs/" + typo.getId() + "/delete").with(csrf()).with(user("mia").roles("MEMBER")))
                .andExpect(status().isForbidden());
        assertThat(certificateRepository.existsById(typo.getId())).isTrue();
    }
}
