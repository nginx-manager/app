package net.nginxmanager.site;

import net.nginxmanager.audit.AuditAction;
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
    @Autowired
    DirectiveRequestRepository directiveRequests;

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
    void membersCannotAddServerWideDirectives() throws Exception {
        Site site = siteService.create(new SiteForm("docs.local", "", SiteType.STATIC, 80, "/srv/www/docs", null, null),
                "alice", alice.getId());

        mvc.perform(update(site, "alice", "MEMBER")
                        .param("options.serverDirectives", "client_body_timeout 30s;\nlocation /x { access_log /etc/x.log; }"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("관리자만 넣습니다: access_log")))
                .andExpect(content().string(containsString("관리자에게 요청")));
        assertThat(reload(site).getOptions().serverDirectives()).isNull();

        mvc.perform(post("/sites/new").with(csrf()).with(user("alice").roles("MEMBER"))
                        .param("primaryDomain", "new.local").param("type", "STATIC").param("listenHttpPort", "80")
                        .param("staticRoot", "/srv/www/new").param("options.serverDirectives", "include /etc/nginx/x.conf;"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("include")))
                .andExpect(content().string(containsString("사이트를 먼저 저장한 뒤")));
        assertThat(siteRepository.findByPrimaryDomain("new.local")).isEmpty();
    }

    @Test
    void membersKeepDirectivesAnAdminAdded() throws Exception {
        Site site = siteService.create(new SiteForm("docs.local", "", SiteType.STATIC, 80, "/srv/www/docs", null, null)
                .withOptions(SiteModuleOptionsTest.options("serverDirectives", "location /files/ { access_log /var/log/files.log; }")),
                "alice", alice.getId());

        mvc.perform(update(site, "alice", "MEMBER").param("options.serverDirectives",
                        "location /files/ { access_log /var/log/files.log; }\nclient_body_timeout 60s;"))
                .andExpect(status().is3xxRedirection());
        assertThat(reload(site).getOptions().serverDirectives())
                .isEqualTo("location /files/ { access_log /var/log/files.log; }\nclient_body_timeout 60s;");
    }

    @Test
    void membersAskAdminsForServerWideDirectives() throws Exception {
        Site site = siteService.create(new SiteForm("docs.local", "", SiteType.STATIC, 80, "/srv/www/docs", null, null),
                "alice", alice.getId());
        String wanted = "location /files/ { proxy_store on; }";

        mvc.perform(post("/sites/" + site.getId() + "/directive-request").with(csrf()).with(user("alice").roles("MEMBER"))
                        .param("options.serverDirectives", wanted))
                .andExpect(status().is3xxRedirection());
        assertThat(directiveRequests.findBySiteId(site.getId())).get()
                .extracting(DirectiveRequest::getRequestedBy, DirectiveRequest::getDirectives)
                .containsExactly("alice", wanted);
        assertThat(auditLogRepository.findAll()).anyMatch(l -> l.getAction() == AuditAction.DIRECTIVE_REQUEST
                && "requested: proxy_store".equals(l.getDetail()));

        mvc.perform(get("/sites/" + site.getId()).with(user("alice").roles("MEMBER")))
                .andExpect(content().string(containsString("관리자가 검토하고 있습니다")));
        mvc.perform(get("/sites").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("검토할 추가 지시어 요청")));
        mvc.perform(post("/sites/" + site.getId() + "/directive-request/approve").with(csrf())
                        .with(user("alice").roles("MEMBER")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/sites/" + site.getId() + "/directive-request/approve").with(csrf())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().is3xxRedirection());
        assertThat(reload(site).getOptions().serverDirectives()).isEqualTo(wanted);
        assertThat(directiveRequests.findBySiteId(site.getId())).isEmpty();
    }

    @Test
    void adminsRejectRequests() throws Exception {
        Site site = siteService.create(new SiteForm("docs.local", "", SiteType.STATIC, 80, "/srv/www/docs", null, null),
                "alice", alice.getId());
        mvc.perform(post("/sites/" + site.getId() + "/directive-request").with(csrf()).with(user("alice").roles("MEMBER"))
                        .param("options.serverDirectives", "include /etc/x.conf;"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/sites/" + site.getId() + "/directive-request/reject").with(csrf())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().is3xxRedirection());
        assertThat(directiveRequests.findBySiteId(site.getId())).isEmpty();
        assertThat(reload(site).getOptions().serverDirectives()).isNull();
    }

    @Test
    void basicDirectivesNeedNoRequestAndHelpIsShown() throws Exception {
        Site site = siteService.create(new SiteForm("docs.local", "", SiteType.STATIC, 80, "/srv/www/docs", null, null),
                "alice", alice.getId());
        mvc.perform(post("/sites/" + site.getId() + "/directive-request").with(csrf()).with(user("alice").roles("MEMBER"))
                        .param("options.serverDirectives", "client_body_timeout 30s;"))
                .andExpect(status().is3xxRedirection());
        assertThat(directiveRequests.findBySiteId(site.getId())).isEmpty();

        mvc.perform(get("/sites/" + site.getId()).with(user("alice").roles("MEMBER")))
                .andExpect(content().string(containsString("쓸 수 있는 지시어")))
                .andExpect(content().string(containsString("data-insert-directive=\"proxy_request_buffering off;\"")));
    }

    @Test
    void ownersRunTheirSiteWithListenServerNameAndRoot() throws Exception {
        siteService.create(new SiteForm("other.local", "", SiteType.STATIC, 80, "/srv/www/other", null, null), "admin");
        Site site = siteService.create(new SiteForm("docs.local", "", SiteType.STATIC, 80, "/srv/www/docs", null, null),
                "alice", alice.getId());
        String mine = "server_name www.docs.local;\nlisten 8080;\nlocation /files/ { alias /srv/files/; }";
        mvc.perform(update(site, "alice", "MEMBER").param("options.serverDirectives", mine))
                .andExpect(status().is3xxRedirection());
        site = reload(site);
        assertThat(site.getOptions().serverDirectives()).isEqualTo(mine);

        // 다른 사이트의 도메인은 가로채지 못한다
        mvc.perform(update(site, "alice", "MEMBER").param("options.serverDirectives", "server_name other.local;"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("other.local")));
        assertThat(reload(site).getOptions().serverDirectives()).isEqualTo(mine);
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
