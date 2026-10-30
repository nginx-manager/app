package net.nginxmanager.common;

import net.nginxmanager.audit.AuditLogRepository;
import net.nginxmanager.support.IntegrationTest;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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
    @Autowired
    UserRepository userRepository;
    @Autowired
    AuditLogRepository auditLogRepository;
    @Autowired
    SetupState setupState;

    // 사용자가 없는 상태가 가장 좁다: 최초 설정 화면에서도 아이콘이 떠야 한다
    @BeforeEach
    void clean() {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        setupState.reset();
    }

    @Test
    void iconsAreServedBeforeSetupAndLogin() throws Exception {
        for (String path : new String[] {"/favicon.ico", "/favicon.svg", "/favicon-32.png", "/apple-touch-icon.png"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    void pagesLinkIcons() throws Exception {
        mvc.perform(get("/setup"))
                .andExpect(content().string(containsString("href=\"/favicon.svg\"")))
                .andExpect(content().string(containsString("rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\"")));
    }
}
