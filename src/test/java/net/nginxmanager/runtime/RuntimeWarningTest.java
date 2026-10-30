package net.nginxmanager.runtime;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditResult;
import net.nginxmanager.common.AppVersion;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.FakeNginxRuntime;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리도구와 nginx 컨테이너가 맞지 않거나 연결이 끊기면 모든 화면에 경고를 띄운다. */
class RuntimeWarningTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;

    @BeforeEach
    void completeSetup() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    @Test
    void noWarningWhenConnected() throws Exception {
        mvc.perform(get("/sites").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(not(containsString("id=\"runtime-warning\""))));
    }

    @Test
    void unreachableAgentIsShownOnEveryPageAndOnTheDashboard() throws Exception {
        nginx.agent = new AgentInfo(false, null, null, "Connection refused");

        mvc.perform(get("/sites").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("nginx 컨테이너(에이전트)에 연결할 수 없습니다")))
                .andExpect(content().string(containsString("Connection refused")));
    }

    @Test
    void protocolMismatchIsShown() throws Exception {
        nginx.agent = new AgentInfo(true, "2.0.0", AgentNginxRuntime.PROTOCOL + 1, "not supported");

        mvc.perform(get("/").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("API 판이 맞지 않아")));
    }

    @Test
    void livenessDoesNotDependOnNginx() throws Exception {
        nginx.health = () -> HealthResult.failed("nginx master process is not running");

        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void onlyTheAgentProtocolDecidesCompatibility() {
        AppVersion app = mock(AppVersion.class);
        when(app.current()).thenReturn("1.2.0");
        FakeNginxRuntime runtime = new FakeNginxRuntime();
        RuntimeCompatibility compatibility = new RuntimeCompatibility(runtime, app);

        runtime.agent = FakeNginxRuntime.connectedAgent("1.2.0");
        assertThat(compatibility.current()).isEmpty();
        runtime.agent = FakeNginxRuntime.connectedAgent("1.1.0");
        assertThat(compatibility.current()).as("manager만 올린 상태는 정상이다").isEmpty();

        runtime.agent = new AgentInfo(true, "0.9.0", AgentNginxRuntime.MIN_AGENT_PROTOCOL - 1, null);
        assertThat(compatibility.current()).hasValueSatisfying(w -> {
            assertThat(w.kind()).isEqualTo(RuntimeCompatibility.Kind.PROTOCOL);
            assertThat(w.agentVersion()).isEqualTo("0.9.0");
            assertThat(w.appVersion()).isEqualTo("1.2.0");
        });
    }

    @Test
    void applyIsUndoneWhenTheAgentDisappearsAfterValidation() {
        siteService.create(new SiteForm("gone.local", "", SiteType.PROXY, 80, null, "http://127.0.0.1:8080", null),
                "admin");
        nginx.validation = () -> {
            nginx.unreachable = true;
            return FakeNginxRuntime.valid();
        };

        assertThatThrownBy(() -> applyService.applyPending("admin", "끊김")).isInstanceOf(NginxCommandException.class);
        assertThat(nginx.reloadCalls).isZero();
        assertThat(CONFIG_ROOT.resolve("conf.d/sites/gone.local.conf")).doesNotExist();
    }

    @Test
    void unreachableAgentBlocksApplyWithoutWritingAnything() throws Exception {
        siteService.create(new SiteForm("blocked.local", "", SiteType.PROXY, 80, null, "http://127.0.0.1:8080", null),
                "admin");
        nginx.agent = new AgentInfo(false, null, null, "Connection refused");
        nginx.unreachable = true;

        mvc.perform(get("/").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("nginx 컨테이너(에이전트)에 연결할 수 없습니다")));
        mvc.perform(get("/apply").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("nginx에 연결할 수 없어 지금은 적용할 수 없습니다")))
                .andExpect(content().string(not(containsString("action=\"/apply\""))));

        // 화면을 열어 둔 채 끊긴 경우: 버튼이 남아 있어도 파일을 쓰지 않고 이유를 보여 준다
        mvc.perform(post("/apply").with(csrf()).with(user("admin").roles("ADMIN")).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("적용하지 못했습니다")));

        assertThat(nginx.validateCalls).isZero();
        assertThat(CONFIG_ROOT.resolve("conf.d/sites/blocked.local.conf")).doesNotExist();
        assertThat(configFileRepository.findByPath("conf.d/sites/blocked.local.conf"))
                .hasValueSatisfying(f -> assertThat(f.hasPendingChange()).isTrue());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(auditLogRepository.findAll()).anySatisfy(l -> {
                    assertThat(l.getAction()).isEqualTo(AuditAction.APPLY);
                    assertThat(l.getResult()).isEqualTo(AuditResult.FAILURE);
                    assertThat(l.getUsername()).isEqualTo("admin");
                }));
    }
}
