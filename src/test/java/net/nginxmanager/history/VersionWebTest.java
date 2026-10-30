package net.nginxmanager.history;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VersionWebTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;

    long first;
    long second;

    @BeforeEach
    void twoVersions() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
        var site = siteService.create(new SiteForm("app.local", "", SiteType.PROXY, 80, null, "http://first:1", null), "admin");
        first = applyService.applyPending("admin", "첫 버전").versionId();
        var loaded = siteRepository.findById(site.getId()).orElseThrow();
        siteService.update(loaded.getId(), new SiteForm("app.local", "", SiteType.PROXY, 80, null, "http://second:2",
                loaded.getVersion()), "admin");
        second = applyService.applyPending("admin", "둘째 버전").versionId();
    }

    @Test
    void memberCannotSeeWholeHistoryOrRollBack() throws Exception {
        // 멤버는 담당 사이트의 이력만 본다(014). 도메인 없이는 404
        mvc.perform(get("/versions").with(user("vi").roles("MEMBER")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/versions/" + first + "/rollback").with(csrf()).with(user("vi").roles("MEMBER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminSeesHistoryAndDiff() throws Exception {
        mvc.perform(get("/versions").with(user("ad").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("첫 버전")))
                .andExpect(content().string(containsString("둘째 버전")))
                .andExpect(content().string(containsString(">변경 전</th>")))
                .andExpect(content().string(containsString(">변경 후</th>")))
                .andExpect(content().string(containsString("aria-label=\"변경 전 버전으로 #" + first + " 고르기\"")));
        mvc.perform(get("/versions/" + first + "/diff/" + second).with(user("ad").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("-        proxy_pass http://first:1;")))
                .andExpect(content().string(containsString("+        proxy_pass http://second:2;")));
    }

    @Test
    void editorRollsBackFromDetailPage() throws Exception {
        mvc.perform(get("/versions/compare").param("a", String.valueOf(second)).param("b", String.valueOf(first))
                        .with(user("ed").roles("ADMIN")))
                .andExpect(redirectedUrl("/versions/" + first + "/diff/" + second));

        mvc.perform(post("/versions/" + first + "/rollback").with(csrf()).with(user("ed").roles("ADMIN")))
                .andExpect(redirectedUrlPattern("/versions/*"));

        assertThat(readFile("conf.d/sites/app.local.conf")).contains("http://first:1");
    }

    @Test
    void driftBannerAppearsAndImportWorks() throws Exception {
        writeFile("conf.d/sites/app.local.conf", "server { listen 80; server_name app.local; }\n");
        driftDetector.invalidate();

        mvc.perform(get("/sites").with(user("ad").roles("ADMIN")))
                .andExpect(content().string(containsString("서버에서 직접 바뀐 설정 파일이")));
        // 외부 변경 확인은 관리자 일이라 멤버에게는 띄우지 않는다
        mvc.perform(get("/sites").with(user("vi").roles("MEMBER")))
                .andExpect(content().string(not(containsString("서버에서 직접 바뀐 설정 파일이"))));
        mvc.perform(post("/drift/import").with(csrf()).with(user("ed").roles("ADMIN")))
                .andExpect(redirectedUrlPattern("/versions/*"));
        mvc.perform(get("/sites").with(user("ad").roles("ADMIN")))
                .andExpect(content().string(not(containsString("서버에서 직접 바뀐 설정 파일이"))));
    }
}
