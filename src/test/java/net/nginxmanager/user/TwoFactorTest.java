package net.nginxmanager.user;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditLogRepository;
import net.nginxmanager.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class TwoFactorTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository userRepository;
    @Autowired
    AuditLogRepository auditLogRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    TwoFactorService twoFactor;
    @Autowired
    TwoFactorResetFile resetFile;
    @Autowired
    SetupState setupState;

    @BeforeEach
    void clean() {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        setupState.reset();
        userRepository.save(new User("admin", passwordEncoder.encode(PASSWORD), Role.ADMIN));
        userRepository.save(new User("member", passwordEncoder.encode(PASSWORD), Role.MEMBER));
    }

    private static long nowStep() {
        return Totp.step(Instant.now().getEpochSecond());
    }

    private String secretOf(String username) {
        return userRepository.findByUsername(username).orElseThrow().getTotpSecret();
    }

    /** 켜고 복구 코드를 돌려준다. 켤 때 쓴 칸 다음 칸의 코드로 로그인한다. */
    private List<String> turnOn(String username) {
        twoFactor.prepare(username);
        return twoFactor.enable(username, Totp.code(secretOf(username), nowStep() - 1)).orElseThrow();
    }

    private MockHttpSession passwordStep(String username) throws Exception {
        return (MockHttpSession) mvc.perform(formLogin().user(username).password(PASSWORD))
                .andExpect(redirectedUrl("/login/code"))
                .andReturn().getRequest().getSession(false);
    }

    @Test
    void turningOnNeedsACorrectCodeAndShowsRecoveryCodesOnce() throws Exception {
        mvc.perform(get("/account/2fa").with(user("member").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<svg")));
        String secret = secretOf("member");
        assertThat(secret).hasSize(32);

        mvc.perform(post("/account/2fa").with(csrf()).with(user("member").roles("MEMBER")).param("code", "000000"))
                .andExpect(redirectedUrl("/account/2fa?error"));
        assertThat(userRepository.findByUsername("member").orElseThrow().isTotpEnabled()).isFalse();

        // 다시 열어도 같은 비밀 키(이미 찍은 QR이 그대로 맞다)
        mvc.perform(get("/account/2fa").with(user("member").roles("MEMBER"))).andExpect(status().isOk());
        assertThat(secretOf("member")).isEqualTo(secret);

        mvc.perform(post("/account/2fa").with(csrf()).with(user("member").roles("MEMBER"))
                        .param("code", Totp.code(secret, nowStep())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"recovery-codes\"")));
        User member = userRepository.findByUsername("member").orElseThrow();
        assertThat(member.isTotpEnabled()).isTrue();
        assertThat(member.getRecoveryCodesLeft()).isEqualTo(TwoFactorService.RECOVERY_CODES);
        assertThat(auditLogRepository.findAll()).anyMatch(log -> log.getAction() == AuditAction.TWO_FACTOR);

        mvc.perform(get("/account/2fa").with(user("member").roles("MEMBER"))).andExpect(redirectedUrl("/account"));
    }

    @Test
    void loginNeedsTheCodeBeforeAnyPage() throws Exception {
        turnOn("admin");
        MockHttpSession session = passwordStep("admin");

        mvc.perform(get("/").session(session)).andExpect(redirectedUrl("/login/code"));
        mvc.perform(get("/users").session(session)).andExpect(redirectedUrl("/login/code"));
        mvc.perform(get("/login/code").session(session)).andExpect(status().isOk());

        mvc.perform(post("/login/code").session(session).with(csrf()).param("code", "123456"))
                .andExpect(redirectedUrl("/login/code?error"));
        mvc.perform(post("/login/code").session(session).with(csrf())
                        .param("code", Totp.code(secretOf("admin"), nowStep() + 1)))
                .andExpect(redirectedUrl("/"));
        mvc.perform(get("/users").session(session)).andExpect(status().isOk());
        assertThat(userRepository.findByUsername("admin").orElseThrow().getFailedLoginCount()).isZero();
    }

    @Test
    void usersWithoutTwoFactorLogInAsBefore() throws Exception {
        mvc.perform(formLogin().user("member").password(PASSWORD)).andExpect(redirectedUrl("/"));
    }

    @Test
    void aRecoveryCodeWorksOnce() throws Exception {
        List<String> codes = turnOn("member");

        MockHttpSession first = passwordStep("member");
        mvc.perform(post("/login/code").session(first).with(csrf()).param("code", codes.get(0).toUpperCase()))
                .andExpect(redirectedUrl("/"));
        assertThat(userRepository.findByUsername("member").orElseThrow().getRecoveryCodesLeft())
                .isEqualTo(TwoFactorService.RECOVERY_CODES - 1);

        MockHttpSession second = passwordStep("member");
        mvc.perform(post("/login/code").session(second).with(csrf()).param("code", codes.get(0)))
                .andExpect(redirectedUrl("/login/code?error"));
    }

    @Test
    void wrongCodesLockTheAccountAndAPasswordDoesNotResetTheCount() throws Exception {
        turnOn("member");
        for (int i = 0; i < LoginAttemptListener.MAX_ATTEMPTS - 1; i++) {
            MockHttpSession session = passwordStep("member");
            mvc.perform(post("/login/code").session(session).with(csrf()).param("code", "000000"))
                    .andExpect(redirectedUrl("/login/code?error"));
        }
        MockHttpSession session = passwordStep("member");
        mvc.perform(post("/login/code").session(session).with(csrf()).param("code", "000000"))
                .andExpect(redirectedUrl("/login?error"));

        assertThat(userRepository.findByUsername("member").orElseThrow().getLockedUntil()).isNotNull();
        mvc.perform(get("/").session(session)).andExpect(redirectedUrl("/login"));
        assertThat(auditLogRepository.findAll()).anyMatch(log -> log.getAction() == AuditAction.ACCOUNT_LOCKED);
    }

    @Test
    void turningOffNeedsThePassword() throws Exception {
        turnOn("member");
        mvc.perform(post("/account/2fa/disable").with(csrf()).with(user("member").roles("MEMBER"))
                        .param("currentPassword", "wrong-password"))
                .andExpect(redirectedUrl("/account?2fa-error"));
        assertThat(userRepository.findByUsername("member").orElseThrow().isTotpEnabled()).isTrue();

        mvc.perform(post("/account/2fa/disable").with(csrf()).with(user("member").roles("MEMBER"))
                        .param("currentPassword", PASSWORD))
                .andExpect(redirectedUrl("/account?2fa-off"));
        assertThat(userRepository.findByUsername("member").orElseThrow().isTotpEnabled()).isFalse();
    }

    @Test
    void newRecoveryCodesReplaceTheOldOnes() throws Exception {
        List<String> old = turnOn("member");
        List<String> fresh = twoFactor.regenerateRecoveryCodes("member", PASSWORD).orElseThrow();
        assertThat(fresh).hasSize(TwoFactorService.RECOVERY_CODES).doesNotContainAnyElementsOf(old);

        assertThat(twoFactor.verifyLogin("member", old.get(0))).isEqualTo(TwoFactorService.Result.WRONG);
        assertThat(twoFactor.verifyLogin("member", fresh.get(0))).isEqualTo(TwoFactorService.Result.OK);
    }

    @Test
    void adminResetsAnotherUsersTwoFactor() throws Exception {
        turnOn("member");
        User member = userRepository.findByUsername("member").orElseThrow();

        mvc.perform(get("/users").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/2fa/reset")));
        mvc.perform(post("/users/{id}/2fa/reset", member.getId()).with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("version", String.valueOf(member.getVersion())))
                .andExpect(redirectedUrl("/users?saved"));
        assertThat(userRepository.findByUsername("member").orElseThrow().isTotpEnabled()).isFalse();

        mvc.perform(post("/users/{id}/2fa/reset", member.getId()).with(csrf()).with(user("member").roles("MEMBER"))
                        .param("version", "0"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theServerCommandResetsThroughAFile() throws Exception {
        turnOn("admin");
        Files.createDirectories(resetFile.file().getParent());
        Files.writeString(resetFile.file(), "admin\nnobody\n");

        resetFile.process();

        assertThat(userRepository.findByUsername("admin").orElseThrow().isTotpEnabled()).isFalse();
        assertThat(resetFile.file()).doesNotExist();
        mvc.perform(formLogin().user("admin").password(PASSWORD)).andExpect(redirectedUrl("/"));
    }
}
