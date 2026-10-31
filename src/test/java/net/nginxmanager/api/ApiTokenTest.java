package net.nginxmanager.api;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditLog;
import net.nginxmanager.site.Site;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 033: API 토큰으로 점검 모드를 켜고 끄고 적용한다. */
class ApiTokenTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    SiteService siteService;
    @Autowired
    ApiTokenService tokens;
    @Autowired
    ApiTokenRepository tokenRepository;

    Site site;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        userRepository.save(new User("alice", "{noop}unused-password", Role.MEMBER));
        setupState.reset();
        site = siteService.create(new SiteForm("app.local", "", SiteType.PROXY, 80, null, "http://10.0.0.1:8080", null),
                "admin");
    }

    private static String bearer(String value) {
        return "Bearer " + value;
    }

    @Test
    void adminCreatesATokenAndSeesItOnce() throws Exception {
        MvcResult result = mvc.perform(post("/settings/api-tokens").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("name", "github-deploy"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String value = (String) result.getFlashMap().get("createdValue");
        assertThat(value).startsWith("nmt_").hasSizeGreaterThan(40);
        ApiToken token = tokenRepository.findAll().getFirst();
        assertThat(token.getName()).isEqualTo("github-deploy");
        assertThat(token.getTokenHint()).isEqualTo(value.substring(0, 8));
        assertThat(tokenRepository.findByTokenHash(ApiTokenService.hash(value))).isPresent();

        mvc.perform(get("/settings/api-tokens").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("github-deploy")))
                .andExpect(content().string(containsString(value.substring(0, 8) + "…")))
                .andExpect(content().string(not(containsString(value))));

        mvc.perform(post("/settings/api-tokens").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("name", "github-deploy"))
                .andExpect(flash().attribute("error", containsString("이미")));
        mvc.perform(post("/settings/api-tokens").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("name", "bad name"))
                .andExpect(flash().attribute("error", containsString("40자")));
        mvc.perform(get("/settings/api-tokens").with(user("alice").roles("MEMBER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsMissingWrongAndDeletedTokens() throws Exception {
        mvc.perform(get("/api/v1/sites")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/v1/sites").header("Authorization", bearer("nmt_wrong")))
                .andExpect(status().isUnauthorized());

        ApiTokenService.Created created = tokens.create("ci", "admin");
        mvc.perform(get("/api/v1/sites").header("Authorization", bearer(created.value())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].domain").value("app.local"))
                .andExpect(jsonPath("$[0].type").value("PROXY"))
                .andExpect(jsonPath("$[0].pending").value(true));
        assertThat(tokenRepository.findById(created.token().getId()).orElseThrow().getLastUsedAt()).isNotNull();

        tokens.delete(created.token().getId(), "admin");
        mvc.perform(get("/api/v1/sites").header("Authorization", bearer(created.value())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void togglesMaintenanceAndApplies() throws Exception {
        String token = bearer(tokens.create("ci", "admin").value());
        mvc.perform(post("/api/v1/apply").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"));
        mvc.perform(post("/api/v1/apply").header("Authorization", token))
                .andExpect(jsonPath("$.result").value("NOTHING_TO_APPLY"));

        mvc.perform(post("/api/v1/sites/APP.local/maintenance").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": true, \"apply\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.apply.result").value("SUCCESS"));
        assertThat(readFile(site.getConfigFile().getPath())).contains("@nginx_manager_maintenance");

        // 이미 켜져 있으면 초안을 만들지 않는다
        mvc.perform(post("/api/v1/sites/app.local/maintenance").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": true}"))
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.apply").doesNotExist());

        nginx.validation = () -> FakeNginxRuntime.invalid("/etc/nginx/conf.d/sites/app.local.conf", 3, "boom");
        mvc.perform(post("/api/v1/sites/app.local/maintenance").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": false, \"apply\": true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.apply.result").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.apply.errors[0]").value(containsString("boom")));
        nginx.validation = FakeNginxRuntime::valid;
        mvc.perform(post("/api/v1/apply").header("Authorization", token)).andExpect(status().isOk());
        assertThat(readFile(site.getConfigFile().getPath())).doesNotContain("@nginx_manager_maintenance");

        assertThat(auditLogRepository.findAll()).filteredOn(log -> "token:ci".equals(log.getUsername()))
                .extracting(AuditLog::getAction).contains(AuditAction.SITE_SAVE, AuditAction.APPLY);
    }

    @Test
    void errors() throws Exception {
        String token = bearer(tokens.create("ci", "admin").value());
        mvc.perform(post("/api/v1/sites/none.local/maintenance").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("none.local")));
        mvc.perform(post("/api/v1/sites/app.local/maintenance").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("enabled")));
        // 본문이 없거나 JSON이 아니어도 같은 모양({"error": …})으로 이유를 알린다
        mvc.perform(post("/api/v1/sites/app.local/maintenance").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("JSON")));
        mvc.perform(post("/api/v1/sites/app.local/maintenance").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("enabled=true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("JSON")));
    }
}
