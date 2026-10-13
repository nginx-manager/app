package net.nginxmanager.site;

import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 프록시 세부 설정(백엔드 여러 개, 웹소켓, 타임아웃)이 폼에서 저장되고 다시 보이는지 확인한다. */
class ProxyOptionsWebTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    @Test
    void savesAndShowsProxyOptions() throws Exception {
        mvc.perform(post("/sites/new").with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("primaryDomain", "ws.local").param("extraDomains", "").param("type", "PROXY")
                        .param("listenHttpPort", "80").param("staticRoot", "")
                        .param("proxyTarget", "http://10.0.0.1:3000\r\nhttp://10.0.0.2:3000")
                        .param("proxyWebsocket", "true").param("proxyReadTimeout", "3600")
                        .param("proxyConnectTimeout", "").param("proxyLoadBalancing", "IP_HASH"))
                .andExpect(status().is3xxRedirection());

        Site site = siteRepository.findAll().getFirst();
        assertThat(site.getProxyTarget()).isEqualTo("http://10.0.0.1:3000\nhttp://10.0.0.2:3000");
        assertThat(site.isProxyWebsocket()).isTrue();
        assertThat(site.getProxyReadTimeout()).isEqualTo(3600);
        assertThat(site.getProxyConnectTimeout()).isNull();
        assertThat(site.getProxyLoadBalancing()).isEqualTo(LoadBalancing.IP_HASH);
        ConfigFile file = configFileRepository.findByPath("conf.d/sites/ws.local.conf").orElseThrow();
        assertThat(file.getDraftContent()).contains("ip_hash;", "server 10.0.0.2:3000;",
                "proxy_pass http://backend_ws_local;", "proxy_read_timeout 3600s;",
                "proxy_set_header Connection $connection_upgrade_ws_local;");

        mvc.perform(get("/sites/" + site.getId()).with(user("ed").roles("EDITOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http://10.0.0.1:3000\nhttp://10.0.0.2:3000</textarea>")))
                .andExpect(content().string(containsString("value=\"IP_HASH\" selected")))
                .andExpect(content().string(containsString("value=\"3600\"")));
    }

    @Test
    void mixedSchemesShowAnErrorAndSaveNothing() throws Exception {
        mvc.perform(post("/sites/new").with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("primaryDomain", "mix.local").param("type", "PROXY").param("listenHttpPort", "80")
                        .param("proxyTarget", "http://a:1 https://b:1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("같은 http/https와 같은 경로")));

        assertThat(siteRepository.count()).isZero();
    }
}
