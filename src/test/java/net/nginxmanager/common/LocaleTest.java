package net.nginxmanager.common;

import net.nginxmanager.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;

@IntegrationTest
class LocaleTest {

    @Autowired
    MockMvc mvc;

    @Test
    void koreanWhenBrowserSendsNoLanguage() throws Exception {
        mvc.perform(get("/sites").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString(">대시보드<")));
    }

    @Test
    void followsBrowserLanguage() throws Exception {
        mvc.perform(get("/sites").with(user("admin").roles("ADMIN")).header("Accept-Language", "ja-JP,ja;q=0.9"))
                .andExpect(content().string(containsString(">ダッシュボード<")));
        mvc.perform(get("/sites").with(user("admin").roles("ADMIN")).header("Accept-Language", "zh-TW"))
                .andExpect(content().string(containsString(">仪表盘<")));
        mvc.perform(get("/sites").with(user("admin").roles("ADMIN")).header("Accept-Language", "fr-FR"))
                .andExpect(content().string(containsString(">대시보드<")));
    }

    @Test
    void chosenLanguageIsRememberedAndKeepsQuery() throws Exception {
        mvc.perform(get("/audit?username=kim&lang=en")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(cookie().value("nm-lang", "en"))
                .andExpect(content().string(containsString(">Dashboard<")))
                .andExpect(content().string(containsString("?username=kim&amp;lang=ja")));
    }
}
