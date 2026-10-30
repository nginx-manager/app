package net.nginxmanager.apply;

import net.nginxmanager.config.VersionResult;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.FakeNginxRuntime;
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
        mvc.perform(post("/sites/new").with(csrf()).with(user("ed").roles("ADMIN"))
                        .param("primaryDomain", "app.local")
                        .param("extraDomains", "")
                        .param("type", "STATIC")
                        .param("listenHttpPort", "80")
                        .param("staticRoot", "/srv/www/app"))
                .andExpect(redirectedUrlPattern("/sites/*?saved"));

        mvc.perform(get("/apply").with(user("ed").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("conf.d/sites/app.local.conf")));

        mvc.perform(post("/apply").with(csrf()).with(user("ed").roles("ADMIN")).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("모두 통과했습니다")));

        assertThat(readFile("conf.d/sites/app.local.conf")).contains("root /srv/www/app;");
        assertThat(configVersionRepository.findFirstByOrderByIdDesc()).get()
                .satisfies(v -> assertThat(v.getResult()).isEqualTo(VersionResult.SUCCESS));
    }

    @Test
    void validationErrorShowsWhereAndWhetherThisDraftCausedIt() throws Exception {
        createStaticSite("mine.local");

        // 이번 초안이 아닌 다른 사이트 파일 때문에 실패하면 그 사실을 표시한다
        nginx.validation = () -> FakeNginxRuntime.invalid(CONFIG_ROOT.resolve("conf.d/sites/other.local.conf").toString(),
                4, "unknown directive \"proxy_pas\"");
        mvc.perform(post("/apply").with(csrf()).with(user("ed").roles("ADMIN")).header("HX-Request", "true"))
                .andExpect(content().string(containsString("문법 검증에 실패해 반영하지 않았습니다")))
                .andExpect(content().string(containsString("other.local.conf:4")))
                .andExpect(content().string(containsString("이번 초안이 바꾸지 않은 파일")));
        assertThat(CONFIG_ROOT.resolve("conf.d/sites/mine.local.conf")).doesNotExist();

        nginx.validation = () -> FakeNginxRuntime.invalid(CONFIG_ROOT.resolve("conf.d/sites/mine.local.conf").toString(),
                7, "unknown directive \"rooot\"");
        mvc.perform(post("/apply").with(csrf()).with(user("ed").roles("ADMIN")).header("HX-Request", "true"))
                .andExpect(content().string(containsString("mine.local.conf:7")))
                .andExpect(content().string(not(containsString("이번 초안이 바꾸지 않은 파일"))));
    }

    private void createStaticSite(String domain) throws Exception {
        mvc.perform(post("/sites/new").with(csrf()).with(user("ed").roles("ADMIN"))
                        .param("primaryDomain", domain)
                        .param("extraDomains", "")
                        .param("type", "STATIC")
                        .param("listenHttpPort", "80")
                        .param("staticRoot", "/srv/www/" + domain))
                .andExpect(redirectedUrlPattern("/sites/*?saved"));
    }
}
