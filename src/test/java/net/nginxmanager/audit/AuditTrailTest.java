package net.nginxmanager.audit;

import net.nginxmanager.apply.ApplyResult;
import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.history.RollbackService;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.FakeNginxRuntime;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import net.nginxmanager.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 도구를 통한 변경이 모두 작성자까지 감사 로그와 이력에 남는지 본다(FR-008, FR-017, US6-2, SC-006).
 * 두 사람이 같은 대상을 고치면 나중 사람에게 충돌을 알리고 덮어쓰지 않는다(FR-018).
 */
class AuditTrailTest extends ConfigRootTest {

    static final String PATH = "conf.d/sites/audit.local.conf";

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;
    @Autowired
    RollbackService rollbackService;
    @Autowired
    UserService userService;

    @BeforeEach
    void users() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        userRepository.save(new User("editor", "{noop}unused-password", Role.EDITOR));
        setupState.reset();
    }

    private static SiteForm proxy(String target, Long version) {
        return new SiteForm("audit.local", "", SiteType.PROXY, 80, null, target, version);
    }

    @Test
    void everyChangeIsTracedToItsAuthor() throws Exception {
        Site site = siteService.create(proxy("http://10.0.0.1:3000", null), "editor");
        ApplyResult first = applyService.applyPending("editor", "처음");

        siteService.update(site.getId(), proxy("http://10.0.0.2:3000",
                siteRepository.findById(site.getId()).orElseThrow().getVersion()), "editor");
        nginx.validation = () -> FakeNginxRuntime.invalid("/etc/nginx/" + PATH, 3, "unknown directive");
        ApplyResult failed = applyService.applyPending("editor", "틀림");
        nginx.validation = FakeNginxRuntime::valid;
        ApplyResult second = applyService.applyPending("editor", "둘째");

        ApplyResult rollback = rollbackService.rollback(first.versionId(), "admin");
        User editor = userRepository.findByUsername("editor").orElseThrow();
        userService.changeRole(editor.getId(), Role.VIEWER, editor.getVersion(), "admin");

        // 이력에 작성자가 남는다
        assertThat(List.of(first, second)).allSatisfy(r -> assertThat(version(r).getAuthorName()).isEqualTo("editor"));
        assertThat(version(rollback).getAuthorName()).isEqualTo("admin");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(auditLogRepository.findAll())
                .extracting(AuditLog::getUsername, AuditLog::getAction, AuditLog::getTarget, AuditLog::getResult)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("editor", AuditAction.SITE_SAVE, "site:audit.local",
                                AuditResult.SUCCESS),
                        org.assertj.core.groups.Tuple.tuple("editor", AuditAction.APPLY, "version:" + first.versionId(),
                                AuditResult.SUCCESS),
                        org.assertj.core.groups.Tuple.tuple("editor", AuditAction.APPLY, "version:" + failed.versionId(),
                                AuditResult.FAILURE),
                        org.assertj.core.groups.Tuple.tuple("editor", AuditAction.APPLY, "version:" + second.versionId(),
                                AuditResult.SUCCESS),
                        org.assertj.core.groups.Tuple.tuple("admin", AuditAction.ROLLBACK,
                                "version:" + rollback.versionId(), AuditResult.SUCCESS)));
        assertThat(auditLogRepository.findAll()).anySatisfy(log -> {
            assertThat(log.getAction()).isEqualTo(AuditAction.USER_CHANGE);
            assertThat(log.getUsername()).isEqualTo("admin");
            assertThat(log.getTarget()).contains("editor");
        });
        assertThat(auditLogRepository.findAll())
                .filteredOn(log -> log.getResult() == AuditResult.FAILURE)
                .singleElement().satisfies(log -> assertThat(log.getDetail()).isEqualTo("VALIDATION_FAILED"));

        // 감사 로그 화면에 사용자·대상·결과가 보인다
        mvc.perform(get("/audit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("version:" + failed.versionId())))
                .andExpect(content().string(containsString("site:audit.local")));
    }

    private ConfigVersion version(ApplyResult result) {
        return configVersionRepository.findById(result.versionId()).orElseThrow();
    }

    @Test
    void secondSaveOfTheSameSiteFormGetsAConflict() throws Exception {
        Site site = siteService.create(proxy("http://10.0.0.1:3000", null), "editor");
        String read = Long.toString(siteRepository.findById(site.getId()).orElseThrow().getVersion());

        mvc.perform(saveSite(site.getId(), "http://10.0.0.2:3000", read)).andExpect(status().is3xxRedirection());
        mvc.perform(saveSite(site.getId(), "http://10.0.0.3:3000", read))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("다른 사람이 먼저 저장했습니다")));

        assertThat(configFileRepository.findByPath(PATH).orElseThrow().getDraftContent())
                .contains("10.0.0.2:3000").doesNotContain("10.0.0.3:3000");
    }

    @Test
    void rawEditorSaveBasedOnAnOldReadGetsAConflict() throws Exception {
        Site site = siteService.create(proxy("http://10.0.0.1:3000", null), "editor");
        ConfigFile file = configFileRepository.findByPath(PATH).orElseThrow();
        String read = Long.toString(file.getVersion());

        // 한 사람은 폼으로, 다른 사람은 원문 편집기로 같은 파일을 연 상태
        mvc.perform(saveSite(site.getId(), "http://10.0.0.2:3000",
                        Long.toString(siteRepository.findById(site.getId()).orElseThrow().getVersion())))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/files/" + file.getId()).with(csrf()).with(user("other").roles("EDITOR"))
                        .param("content", "server { listen 80; }\n").param("version", read))
                .andExpect(status().isConflict());

        assertThat(configFileRepository.findByPath(PATH).orElseThrow().getDraftContent()).contains("10.0.0.2:3000");
        assertThat(siteRepository.findById(site.getId()).orElseThrow().getType()).isEqualTo(SiteType.PROXY);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder saveSite(long id, String target,
                                                                                               String version) {
        return post("/sites/" + id).with(csrf()).with(user("editor").roles("EDITOR"))
                .param("primaryDomain", "audit.local").param("type", "PROXY").param("listenHttpPort", "80")
                .param("proxyTarget", target).param("version", version);
    }
}
