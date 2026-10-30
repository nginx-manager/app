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
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 사이트 복제(024): 새 사이트 폼을 그 사이트 값으로 채운다. */
class SiteCopyWebTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    SiteService siteService;

    User alice;
    Site proxySite;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        alice = userRepository.save(new User("alice", "{noop}unused-password", Role.MEMBER));
        setupState.reset();
        SiteOptions options = SiteModuleOptionsTest.options("rateLimit", 20, "authUsers", "kim:password-123",
                "responseHeaders", "X-Robots-Tag: noindex", "pathRoutes", "/api/ http://10.0.0.5:8080");
        proxySite = siteService.create(new SiteForm("app.local", "www.app.local", SiteType.PROXY, 8080, null,
                "http://10.0.0.1:3000", null, true, true, null, true, 5, 30, null, null, null, null, options), "admin");
    }

    @Test
    void copyFillsTheNewSiteFormWithoutDomainsPasswordsOrVersion() throws Exception {
        SiteForm form = (SiteForm) mvc.perform(get("/sites/new").param("copy", String.valueOf(proxySite.getId()))
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app.local의 설정을 복사했습니다")))
                .andReturn().getModelAndView().getModel().get("form");

        assertThat(form.primaryDomain()).isEmpty();
        assertThat(form.extraDomains()).isEmpty();
        assertThat(form.version()).isNull();
        assertThat(form.type()).isEqualTo(SiteType.PROXY);
        assertThat(form.listenHttpPort()).isEqualTo(8080);
        assertThat(form.proxyTarget()).isEqualTo("http://10.0.0.1:3000");
        assertThat(form.proxyWebsocket()).isTrue();
        assertThat(form.proxyReadTimeout()).isEqualTo(30);
        assertThat(form.certificateMode()).isEqualTo(CertificateMode.LETS_ENCRYPT);
        assertThat(form.options().rateLimit()).isEqualTo(20);
        assertThat(form.options().responseHeaders()).isEqualTo("X-Robots-Tag: noindex");
        assertThat(form.options().pathRoutes()).isEqualTo("/api/ http://10.0.0.5:8080");
        assertThat(form.options().authUsers()).isNull();
    }

    @Test
    void detailShowsTheCopyButtonAndOthersSitesCannotBeCopied() throws Exception {
        mvc.perform(get("/sites/" + proxySite.getId()).with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("/sites/new?copy=" + proxySite.getId())));
        mvc.perform(get("/sites/new").param("copy", String.valueOf(proxySite.getId()))
                        .with(user("alice").roles("MEMBER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/sites/new").with(user("alice").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("copiedFrom"))
                .andExpect(content().string(not(containsString("의 설정을 복사했습니다"))));
    }
}
