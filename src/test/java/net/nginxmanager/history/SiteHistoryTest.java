package net.nginxmanager.history;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.FakeNginxRuntime;
import net.nginxmanager.support.QueryCounter;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 사이트 하나의 변경 이력과, 멤버가 담당 사이트 이력만 보는지 본다(014). */
class SiteHistoryTest extends ConfigRootTest {

    static final String A = "a.example.com";
    static final String B = "b.example.com";

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;
    @Autowired
    VersionQueryRepository versionQueryRepository;
    @Autowired
    QueryCounter queryCounter;

    User alice;
    final List<Long> aVersions = new ArrayList<>();
    long bOnly;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        alice = userRepository.save(new User("alice", "{noop}unused-password", Role.MEMBER));
        setupState.reset();
        aVersions.clear();
        siteService.create(proxy(A, "http://a:1", null), "alice", alice.getId());
        aVersions.add(apply("a 만들기"));
        siteService.create(proxy(B, "http://b:1", null), "admin");
        bOnly = apply("b 만들기");
        update(A, "http://a:2");
        aVersions.add(apply("a 고치기"));
        // 실패한 적용도 그 사이트의 이력이다
        update(A, "http://a:broken");
        nginx.validation = () -> FakeNginxRuntime.invalid("/etc/nginx/conf.d/sites/" + A + ".conf", 1, "broken");
        aVersions.add(applyService.applyPending("admin", "a 실패").versionId());
        nginx.validation = FakeNginxRuntime::valid;
        siteService.discard(configFileRepository.findByPath("conf.d/sites/" + A + ".conf").orElseThrow().getId(), "admin");
        Site a = site(A);
        siteService.setEnabled(a.getId(), false, a.getVersion(), "admin");
        aVersions.add(apply("a 끄기"));
    }

    private static SiteForm proxy(String domain, String target, Long version) {
        return new SiteForm(domain, "", SiteType.PROXY, 80, null, target, version);
    }

    private Site site(String domain) {
        return siteRepository.findByPrimaryDomain(domain).orElseThrow();
    }

    private void update(String domain, String target) {
        Site site = site(domain);
        siteService.update(site.getId(), proxy(domain, target, site.getVersion()), "admin");
    }

    private long apply(String message) {
        var result = applyService.applyPending("admin", message);
        assertThat(result.succeeded()).as(message).isTrue();
        return result.versionId();
    }

    @Test
    void historyOfASiteListsOnlyVersionsThatChangedIt() {
        List<Long> touched = versionQueryRepository.versionsTouching(
                List.of("conf.d/sites/" + A + ".conf", "sites-disabled/" + A + ".conf", "htpasswd/" + A));

        assertThat(touched).containsExactlyElementsOf(aVersions.reversed()).doesNotContain(bOnly);
    }

    @Test
    void adminFiltersHistoryAndDiffShowsOnlyThatSite() throws Exception {
        mvc.perform(get("/versions").param("domain", A).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("a 고치기")))
                .andExpect(content().string(not(containsString("b 만들기"))));
        // 첫 버전(a만 있음)에서 b를 만든 뒤 a를 고친 버전까지: b 파일은 빼고 보인다
        mvc.perform(get("/versions/" + aVersions.get(0) + "/diff/" + aVersions.get(1)).param("domain", A)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("http://a:2")))
                .andExpect(content().string(not(containsString(B + ".conf"))));
    }

    @Test
    void memberSeesOnlyTheirSiteHistoryWithoutRollback() throws Exception {
        long latest = aVersions.getLast();
        mvc.perform(get("/versions").param("domain", A).with(user("alice").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("a 끄기")));
        mvc.perform(get("/versions/" + latest).with(user("alice").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(A + ".conf")))
                .andExpect(content().string(not(containsString(B + ".conf"))))
                .andExpect(content().string(not(containsString("/rollback"))));
        mvc.perform(get("/versions/" + aVersions.get(0) + "/diff/" + latest).with(user("alice").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("http://b:1"))));

        mvc.perform(get("/versions").param("domain", B).with(user("alice").roles("MEMBER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/versions/" + bOnly).with(user("alice").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("http://b:1"))));
        mvc.perform(post("/versions/" + latest + "/rollback").with(csrf()).with(user("alice").roles("MEMBER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/drift/import").with(csrf()).with(user("alice").roles("MEMBER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void siteDetailLinksToItsHistory() throws Exception {
        mvc.perform(get("/sites/" + site(A).getId()).with(user("alice").roles("MEMBER")))
                .andExpect(content().string(containsString("/versions?domain=" + A)));
    }

    @Test
    void filteringDoesNotGrowQueriesWithVersions() {
        List<String> paths = List.of("conf.d/sites/" + A + ".conf");
        long before = queryCounter.count(() -> versionQueryRepository.versionsTouching(paths));
        for (int i = 3; i < 8; i++) {
            update(B, "http://b:" + i);
            apply("b " + i);
        }
        long after = queryCounter.count(() -> versionQueryRepository.versionsTouching(paths));
        assertThat(after).isEqualTo(before);
    }
}
