package net.nginxmanager.user;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditLogRepository;
import net.nginxmanager.audit.AuditResult;
import net.nginxmanager.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class SecurityFlowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository userRepository;
    @Autowired
    AuditLogRepository auditLogRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    SetupState setupState;

    @BeforeEach
    void clean() {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        setupState.reset();
    }

    @Test
    void withoutUsersEveryPageRedirectsToSetup() throws Exception {
        mvc.perform(get("/")).andExpect(redirectedUrl("/setup"));
        mvc.perform(get("/login")).andExpect(redirectedUrl("/setup"));
        mvc.perform(get("/setup")).andExpect(status().isOk());
    }

    @Test
    void setupCreatesAdminOnceThenLoginWorks() throws Exception {
        mvc.perform(post("/setup").with(csrf())
                        .param("username", "admin")
                        .param("password", "correct-horse-battery")
                        .param("passwordConfirm", "correct-horse-battery"))
                .andExpect(redirectedUrl("/login?setup"));

        assertThat(userRepository.findByUsername("admin")).get()
                .extracting(User::getRole).isEqualTo(Role.ADMIN);
        mvc.perform(get("/setup")).andExpect(status().isNotFound());

        mvc.perform(formLogin().user("admin").password("correct-horse-battery")).andExpect(authenticated());
        assertThat(auditLogRepository.findAll())
                .anySatisfy(log -> {
                    assertThat(log.getAction()).isEqualTo(AuditAction.LOGIN);
                    assertThat(log.getResult()).isEqualTo(AuditResult.SUCCESS);
                });
    }

    @Test
    void setupRejectsMismatchedPasswords() throws Exception {
        mvc.perform(post("/setup").with(csrf())
                        .param("username", "admin")
                        .param("password", "correct-horse-battery")
                        .param("passwordConfirm", "something-else-entirely"))
                .andExpect(status().isOk());
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void fiveFailuresLockTheAccount() throws Exception {
        userRepository.save(new User("editor", passwordEncoder.encode("correct-horse-battery"), Role.EDITOR));

        for (int i = 0; i < LoginAttemptListener.MAX_ATTEMPTS; i++) {
            mvc.perform(formLogin().user("editor").password("wrong")).andExpect(unauthenticated());
        }

        mvc.perform(formLogin().user("editor").password("correct-horse-battery")).andExpect(unauthenticated());
        assertThat(userRepository.findByUsername("editor").orElseThrow().getLockedUntil()).isNotNull();
        assertThat(auditLogRepository.findAll()).anyMatch(log -> log.getAction() == AuditAction.ACCOUNT_LOCKED);
    }

    @Test
    void viewerCanReadButNotChange() throws Exception {
        userRepository.save(new User("viewer", "x", Role.VIEWER));

        mvc.perform(get("/").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(post("/anything").with(csrf()).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/users").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
    }

    @Test
    void adminInheritsEditorAndViewer() throws Exception {
        userRepository.save(new User("admin", "x", Role.ADMIN));

        mvc.perform(get("/").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    }
}
