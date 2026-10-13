package net.nginxmanager.apply;

import net.nginxmanager.config.VersionResult;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApplyWebFlowTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;

    @BeforeEach
    void completeSetup() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    @Test
    void editorCreatesSiteThenAppliesIt() throws Exception {
        mvc.perform(post("/sites/new").with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("primaryDomain", "app.local")
                        .param("extraDomains", "")
                        .param("type", "STATIC")
                        .param("listenHttpPort", "80")
                        .param("staticRoot", "/srv/www/app"))
                .andExpect(redirectedUrlPattern("/sites/*?saved"));

        mvc.perform(get("/apply").with(user("ed").roles("EDITOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("conf.d/sites/app.local.conf")));

        mvc.perform(post("/apply").with(csrf()).with(user("ed").roles("EDITOR")).header("HX-Request", "true"))
                .andExpect(status().isOk());

        assertThat(readFile("conf.d/sites/app.local.conf")).contains("root /srv/www/app;");
        assertThat(configVersionRepository.findFirstByOrderByIdDesc()).get()
                .satisfies(v -> assertThat(v.getResult()).isEqualTo(VersionResult.SUCCESS));
    }

    @Test
    void viewerCannotCreateOrApply() throws Exception {
        mvc.perform(post("/sites/new").with(csrf()).with(user("vi").roles("VIEWER"))
                        .param("primaryDomain", "app.local").param("type", "STATIC")
                        .param("listenHttpPort", "80").param("staticRoot", "/srv"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/apply").with(csrf()).with(user("vi").roles("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/sites").with(user("vi").roles("VIEWER")))
                .andExpect(status().isOk());
    }
}
