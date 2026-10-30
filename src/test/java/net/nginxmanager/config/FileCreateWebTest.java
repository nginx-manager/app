package net.nginxmanager.config;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 원문 편집기의 새 파일 만들기와 지우기(spec 002 US5). */
class FileCreateWebTest extends ConfigRootTest {

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

    @Test
    void createsADraftThatIsAppliedLikeAnyOtherFile() throws Exception {
        mvc.perform(post("/files/new").with(csrf()).with(user("ed").roles("ADMIN"))
                        .param("path", "njs/hello.js").param("content", "function hello(r) { r.return(200); }\r\n"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/files/*?saved"));

        ConfigFile file = configFileRepository.findByPath("njs/hello.js").orElseThrow();
        assertThat(file.getDraftContent()).isEqualTo("function hello(r) { r.return(200); }\n");
        assertThat(readFile("njs/hello.js")).isNull();

        assertThat(applyService.applyPending("ed", "njs").succeeded()).isTrue();
        assertThat(readFile("njs/hello.js")).isEqualTo("function hello(r) { r.return(200); }\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc/passwd", "/etc/passwd", "certs/x.pem", "htpasswd/app.local", ".hidden",
            "a/../b.conf", "a b.conf", "a;b.conf", ""})
    void rejectsPathsOutsideWhatMayBeCreated(String path) throws Exception {
        mvc.perform(post("/files/new").with(csrf()).with(user("ed").roles("ADMIN")).param("path", path))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("files/new")));

        assertThat(configFileRepository.count()).isZero();
    }

    @Test
    void rejectsExistingFiles() throws Exception {
        writeFile("stream.d/a.conf", "# a\n");

        mvc.perform(post("/files/new").with(csrf()).with(user("ed").roles("ADMIN")).param("path", "stream.d/a.conf"))
                .andExpect(status().isOk());

        assertThat(configFileRepository.findByPath("stream.d/a.conf")).isEmpty();
    }

    @Test
    void deletesAnUnrelatedFileThroughADraft() throws Exception {
        ConfigFile created = siteService.createFile("stream.d/old.conf", "# old\n", "ed");
        assertThat(applyService.applyPending("ed", "create").succeeded()).isTrue();
        ConfigFile file = configFileRepository.findByPath("stream.d/old.conf").orElseThrow();

        mvc.perform(get("/files/" + created.getId()).with(user("ed").roles("ADMIN")))
                .andExpect(content().string(containsString("/delete")));
        mvc.perform(post("/files/" + file.getId() + "/delete").with(csrf()).with(user("ed").roles("ADMIN"))
                        .param("version", Long.toString(file.getVersion())))
                .andExpect(status().is3xxRedirection());

        assertThat(configFileRepository.findByPath("stream.d/old.conf").orElseThrow().isDraftDelete()).isTrue();
        assertThat(applyService.applyPending("ed", "delete").succeeded()).isTrue();
        assertThat(readFile("stream.d/old.conf")).isNull();
        assertThat(configFileRepository.findByPath("stream.d/old.conf")).isEmpty();
    }

    @Test
    void deletingANeverAppliedFileDropsTheDraft() throws Exception {
        ConfigFile created = siteService.createFile("misc/note.conf", "# note\n", "ed");

        mvc.perform(post("/files/" + created.getId() + "/delete").with(csrf()).with(user("ed").roles("ADMIN"))
                        .param("version", Long.toString(created.getVersion())))
                .andExpect(status().is3xxRedirection());

        assertThat(configFileRepository.findByPath("misc/note.conf")).isEmpty();
    }

    @Test
    void siteFilesAndNginxConfCannotBeDeletedHere() throws Exception {
        writeFile("nginx.conf", "events {}\n");
        ConfigFile main = configFileRepository.save(new ConfigFile("nginx.conf", false));
        ConfigFile site = configFileRepository.save(new ConfigFile("conf.d/sites/a.local.conf", false));

        mvc.perform(get("/files/" + main.getId()).with(user("ed").roles("ADMIN")))
                .andExpect(content().string(not(containsString("/delete"))));
        assertThat(siteService.isDeletable(site)).isFalse();
    }

    @Test
    void viewersCannotCreate() throws Exception {
        mvc.perform(post("/files/new").with(csrf()).with(user("v").roles("MEMBER")).param("path", "a.conf"))
                .andExpect(status().isForbidden());
    }
}
