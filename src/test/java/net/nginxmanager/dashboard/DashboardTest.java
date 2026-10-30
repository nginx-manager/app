package net.nginxmanager.dashboard;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditResult;
import net.nginxmanager.cert.CertificateUploadService;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.FakeNginxRuntime;
import net.nginxmanager.support.QueryCounter;
import net.nginxmanager.support.TestCertificates;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;
    @Autowired
    DashboardService dashboardService;
    @Autowired
    CertificateUploadService uploadService;
    @Autowired
    QueryCounter queryCounter;

    @BeforeEach
    void completeSetup() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    private void site(String domain) {
        siteService.create(new SiteForm(domain, "", SiteType.PROXY, 80, null, "http://127.0.0.1:8080", null), "admin");
    }

    @Test
    void showsStatusSitesRecentAppliesAndCertificateWarnings() throws Exception {
        site("a.local");
        site("b.local");
        applyService.applyPending("admin", "두 사이트");
        Instant now = Instant.now();
        TestCertificates.Issued soon = TestCertificates.selfSigned(List.of("soon.example.com"),
                now.minusSeconds(80L * 24 * 3600), now.plusSeconds(10L * 24 * 3600));
        uploadService.upload("soon", soon.certPem(), soon.keyPem(), "admin");

        DashboardService.Dashboard dashboard = dashboardService.load();
        assertThat(dashboard.siteCount()).isEqualTo(2);
        assertThat(dashboard.enabledSiteCount()).isEqualTo(2);
        assertThat(dashboard.recentVersions()).first().satisfies(v -> assertThat(v.message()).isEqualTo("두 사이트"));
        assertThat(dashboard.certificateWarnings()).singleElement()
                .satisfies(w -> assertThat(w.daysLeft()).isBetween(9L, 10L));
        assertThat(dashboard.databaseBytes()).isPositive();

        mvc.perform(get("/").with(user("v").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("실행 중")))
                .andExpect(content().string(containsString("2 활성 / 2 전체")))
                .andExpect(content().string(containsString("인증서 확인 필요")))
                .andExpect(content().string(containsString("1.31.6")));
    }

    @Test
    void dashboardQueriesDoNotGrowWithSites() {
        queryCounter.assertConstantQueries(
                n -> {
                    for (int i = 0; i < n; i++) {
                        site("s" + n + "-" + i + ".local");
                    }
                    applyService.applyPending("admin", "seed " + n);
                },
                () -> dashboardService.load(),
                1, 20);
    }

    @Test
    void adminStartsStoppedNginxAfterValidation() throws Exception {
        nginx.running = false;

        mvc.perform(get("/").with(user("v").roles("MEMBER")))
                .andExpect(content().string(containsString("중지됨")))
                .andExpect(content().string(not(containsString("nginx 시작"))));
        mvc.perform(get("/").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("nginx 시작")));

        mvc.perform(post("/runtime/start").with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(flash().attributeExists("notice"));

        assertThat(nginx.startCalls).isEqualTo(1);
        assertThat(nginx.validateCalls).isEqualTo(1);
        assertThat(auditLogRepository.findAll()).anySatisfy(log -> {
            assertThat(log.getAction()).isEqualTo(AuditAction.RUNTIME_START);
            assertThat(log.getResult()).isEqualTo(AuditResult.SUCCESS);
        });
    }

    @Test
    void brokenConfigKeepsNginxDownAndShowsWhy() throws Exception {
        nginx.running = false;
        nginx.validation = () -> FakeNginxRuntime.invalid("/etc/nginx/nginx.conf", 3, "unknown directive \"foo\"");

        mvc.perform(post("/runtime/start").with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(flash().attribute("error", containsString("unknown directive")));

        assertThat(nginx.startCalls).isZero();
        assertThat(auditLogRepository.findAll()).anySatisfy(log -> {
            assertThat(log.getAction()).isEqualTo(AuditAction.RUNTIME_START);
            assertThat(log.getResult()).isEqualTo(AuditResult.FAILURE);
        });
    }

    @Test
    void footerShowsAppVersionOnlyAfterLogin() throws Exception {
        mvc.perform(get("/").with(user("v").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<footer class=\"footer\">")))
                .andExpect(content().string(containsString(">manager dev</span></a>")))
                .andExpect(content().string(containsString("class=\"brand-version\">dev</span>")))
                .andExpect(content().string(containsString("class=\"icon icon-github\"")))
                .andExpect(content().string(containsString("class=\"nav-manual\" href=\"/guide\"")))
                .andExpect(content().string(containsString("<a href=\"/guide\"><svg")))
                .andExpect(content().string(containsString(">사용 설명서</span></a>\n</footer>")));
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<footer"))))
                .andExpect(content().string(containsString("href=\"https://github.com/nginx-manager/app\"")))
                .andExpect(content().string(containsString("<span>GitHub</span></a>")));
    }
}
