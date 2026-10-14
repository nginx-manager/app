package net.nginxmanager.site;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.config.ConfigFile;
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
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 사이트 폼의 모듈 옵션: 중첩 필드 바인딩, 비밀번호 파일 초안, 버리기, 리디렉션 유형. */
class SiteOptionsWebTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    ApplyService applyService;
    @Autowired
    SiteService siteService;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    private MockHttpServletRequestBuilder newProxy(String domain) {
        return post("/sites/new").with(csrf()).with(user("ed").roles("EDITOR"))
                .param("primaryDomain", domain).param("type", "PROXY").param("listenHttpPort", "80")
                .param("proxyTarget", "http://10.0.0.1:3000");
    }

    private MockHttpServletRequestBuilder update(Site site) {
        return post("/sites/" + site.getId()).with(csrf()).with(user("ed").roles("EDITOR"))
                .param("primaryDomain", site.getPrimaryDomain()).param("version", Long.toString(site.getVersion()))
                .param("type", "PROXY").param("listenHttpPort", "80").param("proxyTarget", "http://10.0.0.1:3000");
    }

    @Test
    void passwordsAreHashedIntoTheHtpasswdDraftOnly() throws Exception {
        mvc.perform(newProxy("in.local").param("options.authUsers", "kim:first-password")
                        .param("options.allowFrom", "10.0.0.0/8").param("options.satisfyAny", "true")
                        .param("options.gzip", "true").param("options.rateLimit", "10"))
                .andExpect(status().is3xxRedirection());

        Site site = siteRepository.findByPrimaryDomain("in.local").orElseThrow();
        assertThat(site.getOptions().authUsers()).isEqualTo("kim");
        assertThat(site.getOptions().gzip()).isTrue();
        ConfigFile htpasswd = configFileRepository.findByPath("htpasswd/in.local").orElseThrow();
        assertThat(htpasswd.getDraftContent()).startsWith("kim:{SSHA}").doesNotContain("first-password");
        assertThat(configFileRepository.findByPath("conf.d/sites/in.local.conf").orElseThrow().getDraftContent())
                .contains("satisfy any;", "allow 10.0.0.0/8;", "auth_basic_user_file htpasswd/in.local;", "gzip on;");

        mvc.perform(get("/sites/" + site.getId()).with(user("ed").roles("EDITOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">kim</textarea>")))
                .andExpect(content().string(not(containsString("first-password"))))
                .andExpect(content().string(not(containsString("{SSHA}"))));

        assertThat(applyService.applyPending("ed", "auth").succeeded()).isTrue();
        assertThat(readFile("htpasswd/in.local")).isEqualTo(htpasswd.getDraftContent());
    }

    @Test
    void keepingANameKeepsItsPasswordAndNewNamesNeedOne() throws Exception {
        mvc.perform(newProxy("keep.local").param("options.authUsers", "kim:first-password"))
                .andExpect(status().is3xxRedirection());
        assertThat(applyService.applyPending("ed", "auth").succeeded()).isTrue();
        String applied = readFile("htpasswd/keep.local");
        Site site = siteRepository.findByPrimaryDomain("keep.local").orElseThrow();

        mvc.perform(update(site).param("options.authUsers", "kim\npark"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("park")));
        assertThat(configFileRepository.findByPath("htpasswd/keep.local").orElseThrow().hasPendingChange()).isFalse();

        mvc.perform(update(site).param("options.authUsers", "kim\npark:second-password"))
                .andExpect(status().is3xxRedirection());
        String draft = configFileRepository.findByPath("htpasswd/keep.local").orElseThrow().getDraftContent();
        assertThat(draft).startsWith(applied).contains("park:{SSHA}");
    }

    @Test
    void removingAllUsersDeletesTheFileAndDiscardRestoresBoth() throws Exception {
        mvc.perform(newProxy("off.local").param("options.authUsers", "kim:first-password"))
                .andExpect(status().is3xxRedirection());
        assertThat(applyService.applyPending("ed", "auth").succeeded()).isTrue();
        Site site = siteRepository.findByPrimaryDomain("off.local").orElseThrow();

        mvc.perform(update(site).param("options.authUsers", "")).andExpect(status().is3xxRedirection());
        ConfigFile htpasswd = configFileRepository.findByPath("htpasswd/off.local").orElseThrow();
        assertThat(htpasswd.isDraftDelete()).isTrue();

        siteService.discard(htpasswd.getId(), "ed");

        Site restored = siteRepository.findByPrimaryDomain("off.local").orElseThrow();
        assertThat(configFileRepository.findByPath("conf.d/sites/off.local.conf").orElseThrow().hasPendingChange()).isFalse();
        assertThat(configFileRepository.findByPath("htpasswd/off.local").orElseThrow().hasPendingChange()).isFalse();
        assertThat(restored.getOptions().authUsers()).isEqualTo("kim");
        assertThat(restored.getAuthHtpasswd()).isEqualTo(readFile("htpasswd/off.local"));
    }

    @Test
    void discardingANewSiteRemovesItsHtpasswdDraftToo() throws Exception {
        mvc.perform(newProxy("gone.local").param("options.authUsers", "kim:first-password"))
                .andExpect(status().is3xxRedirection());
        ConfigFile file = configFileRepository.findByPath("conf.d/sites/gone.local.conf").orElseThrow();

        siteService.discard(file.getId(), "ed");

        assertThat(siteRepository.findByPrimaryDomain("gone.local")).isEmpty();
        assertThat(configFileRepository.findByPath("htpasswd/gone.local")).isEmpty();
    }

    @Test
    void appliedSitesWithOptionsStayFormSites() throws Exception {
        mvc.perform(newProxy("opt.local").param("options.securityHeaders", "true").param("options.cacheMinutes", "5")
                        .param("options.realIpFrom", "10.0.0.0/8").param("options.realIpHeader", "X_REAL_IP"))
                .andExpect(status().is3xxRedirection());
        assertThat(applyService.applyPending("ed", "options").succeeded()).isTrue();

        Site site = siteRepository.findByPrimaryDomain("opt.local").orElseThrow();
        assertThat(site.getType()).isEqualTo(SiteType.PROXY);
        assertThat(site.getOptions().realIpHeader()).isEqualTo(SiteOptions.RealIpHeader.X_REAL_IP);
        assertThat(readFile("conf.d/sites/opt.local.conf")).contains("real_ip_header X-Real-IP;", "proxy_cache cache_opt_local;");
    }

    @Test
    void redirectSiteFromTheForm() throws Exception {
        mvc.perform(post("/sites/new").with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("primaryDomain", "old.local").param("type", "REDIRECT").param("listenHttpPort", "80")
                        .param("redirectTarget", "https://new.example.com").param("redirectCode", "308")
                        .param("redirectKeepPath", "true"))
                .andExpect(status().is3xxRedirection());

        assertThat(configFileRepository.findByPath("conf.d/sites/old.local.conf").orElseThrow().getDraftContent()).contains("return 308 https://new.example.com$request_uri;");
        assertThat(applyService.applyPending("ed", "redirect").succeeded()).isTrue();
        assertThat(siteRepository.findByPrimaryDomain("old.local").orElseThrow().getType()).isEqualTo(SiteType.REDIRECT);

        mvc.perform(get("/sites").with(user("ed").roles("EDITOR")))
                .andExpect(content().string(containsString("리디렉션")));
    }

    @Test
    void invalidOptionShowsAnErrorAndSavesNothing() throws Exception {
        mvc.perform(newProxy("bad.local").param("options.allowFrom", "10.0.0.1; deny all"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CIDR")));

        assertThat(siteRepository.count()).isZero();
    }
}
