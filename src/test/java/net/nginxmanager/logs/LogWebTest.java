package net.nginxmanager.logs;

import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LogWebTest extends ConfigRootTest {

    static final Path LOG_DIR = tempDir();

    @DynamicPropertySource
    static void logDir(DynamicPropertyRegistry registry) {
        registry.add("nginx.runtime.log-dir", LOG_DIR::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    LogTailService tailService;
    @Autowired
    SiteService siteService;

    Site site;

    @BeforeEach
    void setUp() throws IOException {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
        ConfigFile file = configFileRepository.save(new ConfigFile("conf.d/sites/app.local.conf", true));
        site = siteRepository.save(new Site("app.local", SiteType.STATIC, file));
        // 멤버 v가 담당하는 사이트다(006)
        User v = userRepository.save(new User("v", "{noop}unused-password", Role.MEMBER));
        site = siteService.assignOwner(site.getId(), v.getId(), site.getVersion(), "admin");
        Files.createDirectories(LOG_DIR.resolve("sites"));
        Files.writeString(LOG_DIR.resolve("sites/app.local.access.log"),
                "1.2.3.4 \"GET / HTTP/1.1\" 200\n5.6.7.8 \"GET /<script>alert(1)</script> HTTP/1.1\" 404\n");
        Files.writeString(LOG_DIR.resolve("sites/app.local.error.log"), "[error] upstream timed out\n");
    }

    @Test
    void viewerSearchesSiteLogsAndContentIsEscaped() throws Exception {
        mvc.perform(get("/logs/" + site.getId()).param("q", "404").with(user("v").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(1)"))))
                .andExpect(content().string(not(containsString("1.2.3.4"))));

        mvc.perform(get("/logs/" + site.getId()).param("type", "ERROR").with(user("v").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("upstream timed out")));
    }

    @Test
    void otherMembersCannotSeeTheLogs() throws Exception {
        userRepository.save(new User("other", "{noop}unused-password", Role.MEMBER));
        mvc.perform(get("/logs/" + site.getId()).with(user("other").roles("MEMBER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/logs/" + site.getId() + "/stream").with(user("other").roles("MEMBER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/logs/" + site.getId()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void missingLogFileIsExplained() throws Exception {
        Files.delete(LOG_DIR.resolve("sites/app.local.error.log"));

        mvc.perform(get("/logs/" + site.getId()).param("type", "ERROR").with(user("v").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("로그 파일이 아직 없습니다")));
    }

    @Test
    void streamOpensServerSentEvents() throws Exception {
        int before = tailService.openStreams();

        MvcResult result = mvc.perform(get("/logs/" + site.getId() + "/stream").with(user("v").roles("MEMBER")))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        // 처음 연결하면 최근 줄을 먼저 보낸다
        assertThat(result.getResponse().getContentAsString()).contains("event:line").contains("GET / HTTP/1.1");
        assertThat(tailService.openStreams()).isEqualTo(before + 1);
    }

    @Test
    void unknownSiteIs404() throws Exception {
        mvc.perform(get("/logs/999999").with(user("v").roles("MEMBER"))).andExpect(status().isNotFound());
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("nm-logs");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
