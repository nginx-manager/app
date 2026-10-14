package net.nginxmanager.stream;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스트림 프록시 화면: 초안 만들기, 고치기, 지우기, 버리기, 원문 편집, 포트 충돌(spec 002 US1). */
class StreamWebTest extends ConfigRootTest {

    static final String PATH = "stream.d/tcp-5432.conf";

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    ApplyService applyService;
    @Autowired
    SiteService siteService;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    private static MockHttpServletRequestBuilder create(String protocol, int port, String backends) {
        return post("/streams/new").with(csrf()).with(user("ed").roles("EDITOR"))
                .param("protocol", protocol).param("listenPort", Integer.toString(port)).param("backends", backends);
    }

    private StreamProxy createApplied() throws Exception {
        mvc.perform(create("TCP", 5432, "10.0.0.5:5432").param("note", "db"))
                .andExpect(status().is3xxRedirection());
        assertThat(applyService.applyPending("ed", "stream").succeeded()).isTrue();
        return streamProxyRepository.findByConfigPath(PATH).orElseThrow();
    }

    private MockHttpServletRequestBuilder update(StreamProxy stream, String backends) {
        return post("/streams/" + stream.getId()).with(csrf()).with(user("ed").roles("EDITOR"))
                .param("protocol", "TCP").param("listenPort", "5432").param("backends", backends)
                .param("version", Long.toString(stream.getVersion()));
    }

    @Test
    void createsADraftThatApplyWrites() throws Exception {
        mvc.perform(create("TCP", 5432, "10.0.0.5:5432\n10.0.0.6:5432").param("loadBalancing", "LEAST_CONN"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/streams/*?saved"));

        ConfigFile file = configFileRepository.findByPath(PATH).orElseThrow();
        assertThat(file.getDraftContent()).contains("least_conn;", "server 10.0.0.6:5432;", "listen 5432;");
        assertThat(readFile(PATH)).isNull();

        assertThat(applyService.applyPending("ed", "stream").succeeded()).isTrue();
        assertThat(readFile(PATH)).isEqualTo(file.getDraftContent());
        StreamProxy stream = streamProxyRepository.findByConfigPath(PATH).orElseThrow();
        assertThat(stream.isRaw()).isFalse();
        assertThat(stream.hasApplied()).isTrue();

        mvc.perform(get("/streams").with(user("v").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("10.0.0.5:5432, 10.0.0.6:5432")));
        mvc.perform(get("/streams/" + stream.getId()).with(user("v").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("proxy_pass stream_tcp_5432;")));
    }

    @Test
    void updateKeepsTheFileAndDiscardRestoresTheAppliedValues() throws Exception {
        StreamProxy stream = createApplied();

        mvc.perform(update(stream, "10.0.0.9:5432").param("listenPort", "6000"))
                .andExpect(status().is3xxRedirection());
        assertThat(configFileRepository.findByPath(PATH).orElseThrow().getDraftContent())
                .contains("server 10.0.0.9:5432;", "listen 5432;");
        assertThat(configFileRepository.findByPath("stream.d/tcp-6000.conf")).isEmpty();

        siteService.discard(configFileRepository.findByPath(PATH).orElseThrow().getId(), "ed");

        assertThat(streamProxyRepository.findByConfigPath(PATH).orElseThrow().getBackends()).isEqualTo("10.0.0.5:5432");
        assertThat(configFileRepository.findByPath(PATH).orElseThrow().hasPendingChange()).isFalse();
    }

    @Test
    void deleteStagesAFileDeleteAndApplyRemovesTheStream() throws Exception {
        StreamProxy stream = createApplied();

        mvc.perform(post("/streams/" + stream.getId() + "/delete").with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("version", Long.toString(stream.getVersion())))
                .andExpect(status().is3xxRedirection());
        assertThat(configFileRepository.findByPath(PATH).orElseThrow().isDraftDelete()).isTrue();
        assertThat(streamProxyRepository.findByConfigPath(PATH)).isPresent();

        assertThat(applyService.applyPending("ed", "delete").succeeded()).isTrue();
        assertThat(readFile(PATH)).isNull();
        assertThat(streamProxyRepository.findByConfigPath(PATH)).isEmpty();
    }

    @Test
    void discardingANewStreamRemovesIt() throws Exception {
        mvc.perform(create("UDP", 53, "10.0.0.2:53")).andExpect(status().is3xxRedirection());
        ConfigFile file = configFileRepository.findByPath("stream.d/udp-53.conf").orElseThrow();

        siteService.discard(file.getId(), "ed");

        assertThat(streamProxyRepository.count()).isZero();
        assertThat(configFileRepository.findByPath("stream.d/udp-53.conf")).isEmpty();
    }

    @Test
    void rawEditTurnsTheStreamIntoARawStream() throws Exception {
        StreamProxy stream = createApplied();
        ConfigFile file = configFileRepository.findByPath(PATH).orElseThrow();

        siteService.editRaw(file.getId(), readFile(PATH) + "# hand edit\n", file.getVersion(), "ed");

        assertThat(streamProxyRepository.findById(stream.getId()).orElseThrow().isRaw()).isTrue();
        mvc.perform(get("/streams").with(user("ed").roles("EDITOR")))
                .andExpect(content().string(containsString("badge")));
    }

    @Test
    void filesWrittenOutsideTheFormBecomeRawStreams() {
        writeFile("stream.d/tcp-2222.conf", "server { listen 2222; proxy_pass 10.0.0.1:22; }\n");
        writeFile("stream.d/custom.conf", "# not a stream file name\n");

        driftDetector.refresh();
        driftDetector.importDrift("ed");

        StreamProxy stream = streamProxyRepository.findByConfigPath("stream.d/tcp-2222.conf").orElseThrow();
        assertThat(stream.isRaw()).isTrue();
        assertThat(stream.getListenPort()).isEqualTo(2222);
        assertThat(streamProxyRepository.count()).isEqualTo(1);
    }

    @Test
    void portsAlreadyInUseAreRefused() throws Exception {
        createApplied();
        siteService.create(new SiteForm("alt.local", "", SiteType.PROXY, 8080, null, "http://10.0.0.1:3000", null),
                "ed");

        mvc.perform(create("TCP", 5432, "10.0.0.7:5432"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(PATH)));
        mvc.perform(create("TCP", 8080, "10.0.0.7:8080"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alt.local")));
        mvc.perform(create("UDP", 5432, "10.0.0.7:5432")).andExpect(status().is3xxRedirection());
        mvc.perform(create("TCP", 443, "10.0.0.7:443")).andExpect(status().isOk());

        assertThat(streamProxyRepository.count()).isEqualTo(2);
    }

    @Test
    void sitesCannotTakeAPortATcpStreamUses() throws Exception {
        createApplied();
        mvc.perform(create("UDP", 8053, "10.0.0.7:53")).andExpect(status().is3xxRedirection());

        // 반대 방향도 막는다: nginx -t는 통과하지만 reload에서 포트를 열지 못한다
        mvc.perform(post("/sites/new").with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("primaryDomain", "db.local").param("type", "PROXY").param("listenHttpPort", "5432")
                        .param("proxyTarget", "http://10.0.0.1:3000"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(PATH)));
        assertThat(siteRepository.count()).isZero();

        Site site = siteService.create(new SiteForm("db.local", "", SiteType.PROXY, 80, null, "http://10.0.0.1:3000",
                null), "ed");
        mvc.perform(post("/sites/" + site.getId()).with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("primaryDomain", "db.local").param("type", "PROXY").param("listenHttpPort", "5432")
                        .param("proxyTarget", "http://10.0.0.1:3000").param("version", Long.toString(site.getVersion())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(PATH)));
        assertThat(siteRepository.findById(site.getId()).orElseThrow().getListenHttpPort()).isEqualTo(80);

        // UDP 스트림과 같은 번호는 TCP 사이트가 써도 된다
        siteService.create(new SiteForm("dns.local", "", SiteType.PROXY, 8053, null, "http://10.0.0.1:3000", null),
                "ed");
    }

    @Test
    void newFormAndPreviewRender() throws Exception {
        mvc.perform(get("/streams/new").with(user("ed").roles("EDITOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"backends\"")));
        mvc.perform(post("/streams/preview").with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("protocol", "UDP").param("listenPort", "53").param("backends", "10.0.0.2:53"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("listen 53 udp;")));
    }

    @Test
    void viewersCannotChangeStreams() throws Exception {
        mvc.perform(post("/streams/new").with(csrf()).with(user("v").roles("VIEWER"))
                        .param("protocol", "TCP").param("listenPort", "5432").param("backends", "10.0.0.5:5432"))
                .andExpect(status().isForbidden());
    }
}
