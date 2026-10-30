package net.nginxmanager.site;

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

/** 029: 목록에서 고른 사이트를 한꺼번에 켜고 끄고 담당자를 정한다. */
class SiteBulkActionsTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    SiteService siteService;

    User alice;
    Site a;
    Site b;
    Site c;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        alice = userRepository.save(new User("alice", "{noop}unused-password", Role.MEMBER));
        setupState.reset();
        a = siteService.create(staticForm("a.local"), "alice", alice.getId());
        b = siteService.create(staticForm("b.local"), "alice", alice.getId());
        c = siteService.create(staticForm("c.local"), "admin");
    }

    private static SiteForm staticForm(String domain) {
        return new SiteForm(domain, "", SiteType.STATIC, 80, "/srv/www/" + domain, null, null);
    }

    private boolean enabled(Site site) {
        return siteRepository.findById(site.getId()).orElseThrow().isEnabled();
    }

    @Test
    void disablesTheSelectedSitesAndSkipsTheOnesAlreadyOff() throws Exception {
        mvc.perform(post("/sites/bulk").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("action", "disable").param("ids", a.getId().toString(), b.getId().toString())
                        .param("q", "local").param("status", ""))
                .andExpect(redirectedUrl("/sites?q=local"))
                .andExpect(flash().attribute("notice", containsString("2개")));
        assertThat(enabled(a)).isFalse();
        assertThat(enabled(b)).isFalse();
        assertThat(enabled(c)).isTrue();

        mvc.perform(post("/sites/bulk").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("action", "disable").param("ids", a.getId().toString(), c.getId().toString()))
                .andExpect(redirectedUrl("/sites"))
                .andExpect(flash().attribute("notice", containsString("건너뜀 1개")));
        assertThat(enabled(c)).isFalse();

        mvc.perform(post("/sites/bulk").with(csrf()).with(user("alice").roles("MEMBER"))
                        .param("action", "enable").param("ids", a.getId().toString(), b.getId().toString()))
                .andExpect(status().is3xxRedirection());
        assertThat(enabled(a)).isTrue();
        assertThat(enabled(b)).isTrue();
    }

    @Test
    void membersCannotTouchOtherSitesOrAssignOwners() throws Exception {
        mvc.perform(post("/sites/bulk").with(csrf()).with(user("alice").roles("MEMBER"))
                        .param("action", "disable").param("ids", a.getId().toString(), c.getId().toString()))
                .andExpect(status().isNotFound());
        assertThat(enabled(a)).isTrue();

        mvc.perform(post("/sites/bulk").with(csrf()).with(user("alice").roles("MEMBER"))
                        .param("action", "owner").param("ids", a.getId().toString()))
                .andExpect(status().isForbidden());
        assertThat(siteRepository.findById(a.getId()).orElseThrow().getOwnerId()).isEqualTo(alice.getId());
    }

    @Test
    void adminAssignsOwnersAtOnce() throws Exception {
        mvc.perform(post("/sites/bulk").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("action", "owner").param("owner", alice.getId().toString())
                        .param("ids", a.getId().toString(), c.getId().toString()))
                .andExpect(flash().attribute("notice", containsString("1개")));
        assertThat(siteRepository.findById(c.getId()).orElseThrow().getOwnerId()).isEqualTo(alice.getId());

        mvc.perform(post("/sites/bulk").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("action", "owner").param("owner", "")
                        .param("ids", a.getId().toString(), b.getId().toString(), c.getId().toString()))
                .andExpect(flash().attribute("notice", containsString("3개")));
        assertThat(siteRepository.findAll()).allSatisfy(site -> assertThat(site.getOwnerId()).isNull());
    }

    @Test
    void listShowsTheBulkBarToEditors() throws Exception {
        mvc.perform(get("/sites").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("name=\"ids\" form=\"bulk\"")))
                .andExpect(content().string(containsString("value=\"owner\"")));
        mvc.perform(get("/sites").with(user("alice").roles("MEMBER")))
                .andExpect(content().string(containsString("value=\"disable\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("value=\"owner\""))));
        mvc.perform(post("/sites/bulk").with(csrf()).with(user("admin").roles("ADMIN")).param("action", "enable"))
                .andExpect(flash().attribute("notice", "고른 사이트가 없습니다."));
    }
}
