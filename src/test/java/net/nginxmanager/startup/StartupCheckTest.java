package net.nginxmanager.startup;

import net.nginxmanager.apply.ApplyResult;
import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.runtime.StartupCheckReport;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;

/** 업그레이드한 nginx 컨테이너가 기존 설정을 거부한 상태로 시작했을 때(FR-024, FR-025). */
class StartupCheckTest extends ConfigRootTest {

    @Autowired
    StartupCheckService startupCheckService;
    @Autowired
    StartupRecordRepository recordRepository;
    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;
    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
        nginx.running = false;
        // nginx 컨테이너가 깨진 설정으로 새로 떴다
        nginx.bootId = "boot-" + System.nanoTime();
        nginx.startupCheck = new StartupCheckReport(false, """
                nginx: [emerg] unknown directive "ssl_foo" in /etc/nginx/nginx.conf:12
                nginx: configuration file /etc/nginx/nginx.conf test failed
                """, Instant.parse("2026-10-13T01:00:00Z"));
        startupCheckService.refreshIfRestarted();
    }

    /** 컨텍스트를 함께 쓰는 다른 테스트에 시작 검사 실패가 남지 않게 정상으로 다시 뜬 것으로 만든다. */
    @AfterEach
    void restartHealthy() {
        nginx.bootId = "boot-" + System.nanoTime();
        nginx.startupCheck = null;
        startupCheckService.refreshIfRestarted();
    }

    @Test
    void failedStartupCheckIsReadWithFileAndLine() {
        StartupCheck check = startupCheckService.check().orElseThrow();

        assertThat(check.ok()).isFalse();
        assertThat(check.checkedAt()).isNotNull();
        assertThat(check.messages()).singleElement().satisfies(m -> {
            assertThat(m.file()).isEqualTo("/etc/nginx/nginx.conf");
            assertThat(m.line()).isEqualTo(12);
            assertThat(m.message()).contains("unknown directive \"ssl_foo\"");
        });
        // 시작할 때 버전을 기록했다
        assertThat(recordRepository.findTop2ByOrderByIdDesc()).first()
                .satisfies(r -> assertThat(r.getConfigOk()).isFalse());
    }

    @Test
    void dashboardExplainsWhyNginxIsDownAndHowToRecover() throws Exception {
        startupCheckService.record("1.29.0", "0.1.0");
        startupCheckService.record("1.31.6", "0.1.0");

        mvc.perform(get("/").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("시작 시 nginx -t 실패")))
                .andExpect(content().string(containsString("/etc/nginx/nginx.conf:12")))
                .andExpect(content().string(containsString("1.29.0")))
                .andExpect(content().string(containsString("0.1.0-nginx1.29.0")))
                .andExpect(content().string(containsString("nginx 시작")));

        // 다시 띄운 뒤에는 시작 검사 실패를 보여 주지 않는다
        nginx.running = true;
        mvc.perform(get("/").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(not(containsString("시작 검사 실패"))))
                .andExpect(content().string(containsString("nginx 1.29.0 → 1.31.6")));
    }

    @Test
    void versionChangeIsAuditedOnlyWhenVersionsDiffer() {
        startupCheckService.record("1.31.6", "0.1.0");
        auditLogRepository.deleteAll();

        startupCheckService.record("1.31.6", "0.1.0");
        assertThat(startupCheckService.versionChange()).isEmpty();
        assertThat(auditLogRepository.findAll()).noneMatch(l -> l.getAction() == AuditAction.UPGRADE);

        startupCheckService.record("1.31.7", "0.1.0");
        assertThat(startupCheckService.versionChange()).hasValueSatisfying(change -> {
            assertThat(change.nginxChanged()).isTrue();
            assertThat(change.appChanged()).isFalse();
        });
        assertThat(auditLogRepository.findAll()).anySatisfy(l -> {
            assertThat(l.getAction()).isEqualTo(AuditAction.UPGRADE);
            assertThat(l.getDetail()).isEqualTo("nginx 1.31.6 → 1.31.7");
        });
    }

    @Test
    void fixWhileNginxIsDownIsWrittenWithoutReloadThenStartWorks() throws Exception {
        siteService.create(new SiteForm("fix.local", "", SiteType.PROXY, 80, null, "http://127.0.0.1:8080", null),
                "admin");

        ApplyResult result = applyService.applyPending("admin", "고침");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.detail()).contains("reload하지 않았습니다");
        assertThat(nginx.reloadCalls).isZero();
        assertThat(readFile("conf.d/sites/fix.local.conf")).contains("proxy_pass");

        mvc.perform(post("/runtime/start").with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(flash().attributeExists("notice"));
        assertThat(nginx.startCalls).isEqualTo(1);
    }
}
