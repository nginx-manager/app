package net.nginxmanager.common;

import net.nginxmanager.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class FaviconTest {

    @Autowired
    MockMvc mvc;

    @Test
    void iconsAreServedWithoutLogin() throws Exception {
        for (String path : new String[] {"/favicon.ico", "/favicon.svg", "/favicon-32.png", "/apple-touch-icon.png"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    void loginPageLinksIcons() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(content().string(containsString("href=\"/favicon.svg\"")))
                .andExpect(content().string(containsString("rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\"")));
    }
}
