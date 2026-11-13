package net.nginxmanager.site;

import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 031·032·035 화면: 메모는 목록에 보이고, 추가 지시어는 관리자와 그 사이트 담당 멤버가 바꾼다. */
class SiteDirectivesWebTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    SiteService siteService;

    User alice;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        alice = userRepository.save(new User("alice", "{noop}unused-password", Role.MEMBER));
        setupState.reset();
    }

    private static MockHttpServletRequestBuilder update(Site site, String username, String role) {
        return post("/sites/" + site.getId()).with(csrf()).with(user(username).roles(role))
                .param("primaryDomain", site.getPrimaryDomain()).param("version", Long.toString(site.getVersion()))
                .param("type", "STATIC").param("listenHttpPort", "80").param("staticRoot", "/srv/www/docs");
    }

    private Site reload(Site site) {
        return siteRepository.findById(site.getId()).orElseThrow();
    }

    @Test
    void ownersChangeDirectivesLikeAdmins() throws Exception {
        Site site = siteService.create(new SiteForm("docs.local", "", SiteType.STATIC, 80, "/srv/www/docs", null, null),
                "alice", alice.getId());

        mvc.perform(update(site, "admin", "ADMIN").param("options.serverDirectives", "client_body_timeout 30s;"))
                .andExpect(status().is3xxRedirection());
        site = reload(site);
        assertThat(site.getOptions().serverDirectives()).isEqualTo("client_body_timeout 30s;");

        // 담당 멤버(035)도 고치고 비울 수 있다
        mvc.perform(get("/sites/" + site.getId()).with(user("alice").roles("MEMBER")))
                .andExpect(content().string(containsString(">추가 지시어</summary>")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("관리자만 바꿀 수 있습니다."))));
        mvc.perform(update(site, "alice", "MEMBER").param("options.serverDirectives", "proxy_request_buffering off;")
                        .param("options.note", "알리스 담당"))
                .andExpect(status().is3xxRedirection());
        site = reload(site);
        assertThat(site.getOptions().serverDirectives()).isEqualTo("proxy_request_buffering off;");
        assertThat(site.getOptions().note()).isEqualTo("알리스 담당");

        mvc.perform(update(site, "alice", "MEMBER")).andExpect(status().is3xxRedirection());
        assertThat(reload(site).getOptions().serverDirectives()).isNull();
    }

    @Test
    void othersCannotChangeDirectives() throws Exception {
        userRepository.save(new User("bob", "{noop}unused-password", Role.MEMBER));
        Site site = siteService.create(new SiteForm("docs.local", "", SiteType.STATIC, 80, "/srv/www/docs", null, null)
                .withOptions(SiteModuleOptionsTest.options("serverDirectives", "client_body_timeout 30s;")),
                "alice", alice.getId());

        mvc.perform(update(site, "bob", "MEMBER").param("options.serverDirectives", "root /etc;"))
                .andExpect(status().isNotFound());
        assertThat(reload(site).getOptions().serverDirectives()).isEqualTo("client_body_timeout 30s;");
    }

    @Test
    void membersCreateSitesWithDirectives() throws Exception {
        mvc.perform(post("/sites/new").with(csrf()).with(user("alice").roles("MEMBER"))
                        .param("primaryDomain", "new.local").param("type", "STATIC").param("listenHttpPort", "80")
                        .param("staticRoot", "/srv/www/new").param("options.serverDirectives", "client_body_timeout 30s;"))
                .andExpect(status().is3xxRedirection());
        Site site = siteRepository.findByPrimaryDomain("new.local").orElseThrow();
        assertThat(site.getOwnerId()).isEqualTo(alice.getId());
        assertThat(site.getOptions().serverDirectives()).isEqualTo("client_body_timeout 30s;");
    }

    @Test
    void unbalancedDirectivesAreRejected() throws Exception {
        mvc.perform(post("/sites/new").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("primaryDomain", "bad.local").param("type", "STATIC").param("listenHttpPort", "80")
                        .param("staticRoot", "/srv/www/bad").param("options.serverDirectives", "location / {"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("중괄호")));
    }

    @Test
    void listShowsAndSearchesTheNote() throws Exception {
        siteService.create(new SiteForm("pay.local", "", SiteType.STATIC, 80, "/srv/www/pay", null, null)
                .withOptions(SiteModuleOptionsTest.options("note", "결제 팀 운영\n문의 #pay-ops")), "admin");
        siteService.create(new SiteForm("blog.local", "", SiteType.STATIC, 80, "/srv/www/blog", null, null), "admin");
        mvc.perform(get("/sites").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString(">결제 팀 운영</div>")));
        mvc.perform(get("/sites").param("q", "결제").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("pay.local")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("blog.local"))));
    }
}
