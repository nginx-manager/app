package net.nginxmanager.site;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.history.RollbackService;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 사이트를 지우는 초안을 만들고, 적용하면 사이트가 없어지는지 본다(013). */
class SiteDeleteTest extends ConfigRootTest {

    static final String DOMAIN = "old.example.com";

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

    User alice;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        alice = userRepository.save(new User("alice", "{noop}unused-password", Role.MEMBER));
        setupState.reset();
    }

    private static SiteForm staticForm(String domain) {
        return new SiteForm(domain, "", SiteType.STATIC, 80, "/srv/www/" + domain, null, null);
    }

    private Site appliedSite(Long owner) {
        Site site = siteService.create(staticForm(DOMAIN), "admin", owner);
        assertThat(applyService.applyPending("admin", "add").succeeded()).isTrue();
        return siteRepository.findById(site.getId()).orElseThrow();
    }

    private ConfigFile file() {
        return configFileRepository.findByPath(SitePaths.of(DOMAIN, true)).orElseThrow();
    }

    private Site reload(Site site) {
        return siteRepository.findById(site.getId()).orElseThrow();
    }

    @Test
    void deletingStagesADraftAndApplyingRemovesTheSiteSoTheDomainCanBeReused() throws Exception {
        Site site = appliedSite(null);
        String path = SitePaths.of(DOMAIN, true);

        mvc.perform(post("/sites/" + site.getId() + "/delete").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("version", String.valueOf(site.getVersion())))
                .andExpect(redirectedUrl("/sites/" + site.getId() + "?deleting"));

        // 적용 전에는 사이트와 파일이 그대로이고 삭제 대기로 보인다
        assertThat(readFile(path)).isNotNull();
        mvc.perform(get("/sites/" + site.getId()).with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("삭제 대기")))
                .andExpect(content().string(containsString("사이트를 지우는 초안이 있습니다")));
        mvc.perform(get("/sites").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("삭제 대기")));

        assertThat(applyService.applyPending("admin", "delete").succeeded()).isTrue();

        assertThat(readFile(path)).isNull();
        assertThat(siteRepository.findByPrimaryDomain(DOMAIN)).isEmpty();
        assertThat(auditLogRepository.findAll())
                .anySatisfy(l -> assertThat(l.getAction()).isEqualTo(AuditAction.SITE_DELETE));
        // 같은 도메인으로 다시 만들 수 있다
        siteService.create(staticForm(DOMAIN), "admin");
        assertThat(siteRepository.findByPrimaryDomain(DOMAIN)).isPresent();
    }

    @Test
    void disabledSiteIsDeletedWithItsPasswordFile() throws Exception {
        mvc.perform(post("/sites/new").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("primaryDomain", DOMAIN).param("type", "PROXY").param("listenHttpPort", "80")
                        .param("proxyTarget", "http://10.0.0.1:3000").param("options.authUsers", "kim:first-password"))
                .andExpect(status().is3xxRedirection());
        assertThat(applyService.applyPending("admin", "add").succeeded()).isTrue();
        Site site = siteRepository.findByPrimaryDomain(DOMAIN).orElseThrow();
        siteService.setEnabled(site.getId(), false, site.getVersion(), "admin");
        assertThat(applyService.applyPending("admin", "off").succeeded()).isTrue();
        site = reload(site);
        assertThat(readFile(Htpasswd.path(DOMAIN))).isNotNull();

        assertThat(siteService.delete(site.getId(), site.getVersion(), "admin")).isFalse();
        assertThat(applyService.applyPending("admin", "delete").succeeded()).isTrue();

        assertThat(siteRepository.findByPrimaryDomain(DOMAIN)).isEmpty();
        assertThat(readFile(SitePaths.of(DOMAIN, false))).isNull();
        assertThat(readFile(Htpasswd.path(DOMAIN))).isNull();
    }

    @Test
    void discardingTheDraftKeepsTheSite() throws Exception {
        Site site = appliedSite(null);
        siteService.delete(site.getId(), site.getVersion(), "admin");

        siteService.discard(file().getId(), "admin");

        assertThat(file().hasPendingChange()).isFalse();
        assertThat(applyService.applyPending("admin", null).nothingChanged()).isTrue();
        assertThat(siteRepository.findByPrimaryDomain(DOMAIN)).isPresent();
    }

    @Test
    void siteWithUnappliedChangesIsNotDeleted() throws Exception {
        Site site = appliedSite(null);
        siteService.update(site.getId(), new SiteForm(DOMAIN, "", SiteType.STATIC, 80, "/srv/other", null,
                site.getVersion()), "admin");
        site = reload(site);

        mvc.perform(post("/sites/" + site.getId() + "/delete").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("version", String.valueOf(site.getVersion())))
                .andExpect(redirectedUrl("/sites/" + site.getId()))
                .andExpect(flash().attribute("error", containsString("적용하지 않은 변경")));
        assertThat(file().isDraftDelete()).isFalse();
    }

    @Test
    void siteNeverAppliedIsRemovedRightAway() throws Exception {
        Site site = siteService.create(staticForm(DOMAIN), "admin");

        mvc.perform(post("/sites/" + site.getId() + "/delete").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("version", String.valueOf(site.getVersion())))
                .andExpect(redirectedUrl("/sites"))
                .andExpect(flash().attribute("notice", containsString("지웠습니다")));
        assertThat(siteRepository.findByPrimaryDomain(DOMAIN)).isEmpty();
        assertThat(configFileRepository.count()).isZero();
    }

    @Test
    void membersDeleteOnlyTheirSites() throws Exception {
        Site mine = appliedSite(alice.getId());
        Site other = siteService.create(staticForm("other.example.com"), "admin");

        mvc.perform(post("/sites/" + other.getId() + "/delete").with(csrf()).with(user("alice").roles("MEMBER"))
                        .param("version", String.valueOf(other.getVersion())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/sites/" + mine.getId() + "/delete").with(csrf()).with(user("alice").roles("MEMBER"))
                        .param("version", String.valueOf(mine.getVersion())))
                .andExpect(redirectedUrl("/sites/" + mine.getId() + "?deleting"));
        assertThat(siteRepository.findByPrimaryDomain("other.example.com")).isPresent();
    }

    @Test
    void rollingBackBringsTheSiteBack() {
        Site site = appliedSite(null);
        long before = configVersionRepository.findFirstByOrderByIdDesc().orElseThrow().getId();
        siteService.delete(site.getId(), site.getVersion(), "admin");
        assertThat(applyService.applyPending("admin", "delete").succeeded()).isTrue();

        assertThat(rollbackService.rollback(before, "admin").succeeded()).isTrue();

        assertThat(siteRepository.findByPrimaryDomain(DOMAIN)).isPresent();
    }
}
