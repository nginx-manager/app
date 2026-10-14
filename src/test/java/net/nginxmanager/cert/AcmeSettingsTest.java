package net.nginxmanager.cert;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 화면에서 Let's Encrypt 스테이징으로 바꾸면 발급이 스테이징 서버로 간다(FR-030). */
class AcmeSettingsTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;

    @BeforeEach
    void admin() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    @Test
    void stagingChosenOnScreenIsWhereCertificatesComeFrom() throws Exception {
        Acme4jClient client = new Acme4jClient(new AcmeProperties(null, null, null, null), acmeSettingsRepository);
        assertThat(client.directory(new AcmeSettings())).isEqualTo("acme://letsencrypt.org");

        mvc.perform(post("/settings/acme").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("environment", "STAGING").param("contactEmail", "ops@example.com"))
                .andExpect(redirectedUrl("/settings/acme"));

        AcmeSettings saved = acmeSettingsRepository.findFirstByOrderByIdAsc().orElseThrow();
        assertThat(saved.getEnvironment()).isEqualTo(AcmeEnvironment.STAGING);
        assertThat(client.directory(saved)).isEqualTo("acme://letsencrypt.org/staging");
        // 시험용 ACME 서버를 환경 변수로 정하면 화면 선택보다 앞선다
        assertThat(new Acme4jClient(new AcmeProperties("acme://pebble/pebble:14000", null, null, null),
                acmeSettingsRepository).directory(saved)).isEqualTo("acme://pebble/pebble:14000");
    }

    @Test
    void onlyAdminsChangeTheAcmeServer() throws Exception {
        mvc.perform(post("/settings/acme").with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("environment", "STAGING"))
                .andExpect(status().isForbidden());
        assertThat(acmeSettingsRepository.findAll()).isEmpty();
    }
}
