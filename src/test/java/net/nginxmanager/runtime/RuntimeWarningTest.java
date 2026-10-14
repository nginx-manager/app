package net.nginxmanager.runtime;

import net.nginxmanager.apply.ApplyService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                .andExpect(content().string(containsString("서로 맞지 않아")));
    }

    @Test
    void livenessDoesNotDependOnNginx() throws Exception {
        nginx.health = () -> HealthResult.failed("nginx master process is not running");

        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void releaseVersionsAreCompared() {
        AppVersion app = mock(AppVersion.class);
        when(app.current()).thenReturn("1.0.0");
        FakeNginxRuntime runtime = new FakeNginxRuntime();
        RuntimeCompatibility compatibility = new RuntimeCompatibility(runtime, app);

        runtime.agent = FakeNginxRuntime.connectedAgent("1.0.0");
        assertThat(compatibility.current()).isEmpty();
        runtime.agent = FakeNginxRuntime.connectedAgent("v1.0.0");
        assertThat(compatibility.current()).isEmpty();
        runtime.agent = FakeNginxRuntime.connectedAgent("dev");
        assertThat(compatibility.current()).as("development builds are not compared").isEmpty();

        runtime.agent = FakeNginxRuntime.connectedAgent("0.9.0");
        assertThat(compatibility.current()).hasValueSatisfying(w -> {
            assertThat(w.kind()).isEqualTo(RuntimeCompatibility.Kind.VERSION);
            assertThat(w.agentVersion()).isEqualTo("0.9.0");
            assertThat(w.appVersion()).isEqualTo("1.0.0");
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
}
