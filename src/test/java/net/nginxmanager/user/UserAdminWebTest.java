package net.nginxmanager.user;

import net.nginxmanager.support.ConfigRootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAdminWebTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void users() {
        userRepository.save(new User("admin", passwordEncoder.encode("admin-password"), Role.ADMIN));
        userRepository.save(new User("editor", passwordEncoder.encode("editor-password"), Role.EDITOR));
        setupState.reset();
    }

    @Test
    void adminInvitesUserAndSeesItInAuditLog() throws Exception {
        mvc.perform(post("/users").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("username", "newbie").param("password", "newbie-password").param("role", "VIEWER"))
                .andExpect(redirectedUrl("/users?invited=newbie"));

        mvc.perform(get("/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("newbie")));
        mvc.perform(get("/audit").param("action", "USER_CREATE").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("user:newbie role:VIEWER")));
    }

    @Test
    void shortPasswordIsRejected() throws Exception {
        mvc.perform(post("/users").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("username", "newbie").param("password", "short").param("role", "VIEWER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("10자 이상")));
    }

    @Test
    void disablingUserEndsTheirSession() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin().user("editor").password("editor-password"))
                .andReturn().getRequest().getSession();
        mvc.perform(get("/sites").session(session)).andExpect(status().isOk());

        User editor = userRepository.findByUsername("editor").orElseThrow();
        mvc.perform(post("/users/" + editor.getId() + "/enabled").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("enabled", "false").param("version", String.valueOf(editor.getVersion())))
                .andExpect(redirectedUrl("/users?saved"));

        mvc.perform(get("/sites").session(session)).andExpect(redirectedUrl("/login?expired"));
        mvc.perform(formLogin().user("editor").password("editor-password"))
                .andExpect(redirectedUrl("/login?error"));
    }
}
