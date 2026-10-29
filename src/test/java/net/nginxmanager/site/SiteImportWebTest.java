package net.nginxmanager.site;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 설정 파일을 지정해 사이트로 가져오기(spec 005). */
class SiteImportWebTest extends ConfigRootTest {

    /** 호스트에서 마운트한 디렉터리(예: /home) 역할. */
    static final Path HOME = createHome();

    private static final String PROXY = """
            server {
                listen 80;
                server_name app.example.com;
                location / {
                    proxy_pass http://127.0.0.1:3000;
                }
            }
            """;
    private static final String RAW = """
            server {
                listen 8081;
                server_name shop.example.com www.shop.example.com;
                location / { proxy_pass http://127.0.0.1:3000; }
                location /static/ { alias /srv/static/; }
            }
            """;

    @DynamicPropertySource
    static void importRoots(DynamicPropertyRegistry registry) {
        registry.add("nginx-manager.import.roots", HOME::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    ApplyService applyService;
    @Autowired
    ImportSourceRepository sourceRepository;

    @BeforeEach
    void setUp() throws IOException {
        sourceRepository.deleteAll();
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
        try (Stream<Path> paths = Files.walk(HOME)) {
            paths.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(HOME)).forEach(p -> p.toFile().delete());
        }
        writeFile("nginx.conf", "events {}\nhttp { include conf.d/sites/*.conf; }\n");
    }

    @Test
    void importsAFileInTheConfigRootAsAFormSiteAndMovesIt() throws Exception {
        writeFile("conf.d/app.conf", PROXY);

        mvc.perform(get("/sites/import").param("pattern", "conf.d/*.conf").with(user("ed").roles("EDITOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("conf.d/app.conf")))
                .andExpect(content().string(not(containsString("nginx.conf</code>"))));
        mvc.perform(get("/sites/import/file").param("path", "conf.d/app.conf").with(user("ed").roles("EDITOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("proxy_pass http://127.0.0.1:3000;")));

        mvc.perform(post("/sites/import").with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("path", "conf.d/app.conf").param("contentHash", ConfigFileStore.hash(PROXY))
                        .param("primaryDomain", "app.example.com").param("mode", "FORM")
                        .param("removeSource", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/sites/*?imported"));

        Site site = siteRepository.findByPrimaryDomain("app.example.com").orElseThrow();
        assertThat(site.getType()).isEqualTo(SiteType.PROXY);
        assertThat(site.getProxyTarget()).isEqualTo("http://127.0.0.1:3000");
        assertThat(site.getImportPath()).isEqualTo(CONFIG_ROOT.toRealPath().resolve("conf.d/app.conf").toString());
        assertThat(configFileRepository.findByPath("conf.d/app.conf")).get()
                .extracting(ConfigFile::isDraftDelete).isEqualTo(true);
        assertThat(readFile("conf.d/app.conf")).isEqualTo(PROXY);

        assertThat(applyService.applyPending("ed", "import").succeeded()).isTrue();

        assertThat(readFile("conf.d/app.conf")).isNull();
        assertThat(readFile("conf.d/sites/app.example.com.conf")).contains("proxy_pass http://127.0.0.1:3000;");
        Site applied = siteRepository.findByPrimaryDomain("app.example.com").orElseThrow();
        assertThat(applied.getType()).isEqualTo(SiteType.PROXY);
        assertThat(configFileRepository.findByPath("conf.d/sites/app.example.com.conf")).get()
                .extracting(ConfigFile::getImportedFrom).isNull();
        assertThat(auditLogRepository.findAll()).anyMatch(log -> log.getAction().name().equals("SITE_IMPORT"));
    }

    @Test
    void discardingEitherDraftDiscardsBoth() throws Exception {
        writeFile("conf.d/app.conf", PROXY);
        mvc.perform(post("/sites/import").with(csrf()).with(user("ed").roles("EDITOR"))
                .param("path", "conf.d/app.conf").param("contentHash", ConfigFileStore.hash(PROXY))
                .param("mode", "FORM").param("removeSource", "true"));
        ConfigFile source = configFileRepository.findByPath("conf.d/app.conf").orElseThrow();

        mvc.perform(post("/apply/discard/{id}", source.getId()).with(csrf()).with(user("ed").roles("EDITOR")));

        assertThat(siteRepository.findByPrimaryDomain("app.example.com")).isEmpty();
        assertThat(configFileRepository.findAll()).noneMatch(ConfigFile::hasPendingChange);
        assertThat(applyService.applyPending("ed", "nothing").succeeded()).isFalse();
        assertThat(readFile("conf.d/app.conf")).isEqualTo(PROXY);
    }

    @Test
    void scansAGlobPatternInAMountedDirectoryAndImportsEverythingAtOnce() throws Exception {
        writeHome("alice/nginx/app.conf", PROXY);
        writeHome("bob/nginx/shop.conf", RAW);
        writeHome("bob/nginx/notes.txt", "not nginx");
        writeHome("carol/nginx/upstreams.conf", "upstream a { server 10.0.0.1; }\n");

        mvc.perform(post("/sites/import/sources").with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("pattern", HOME + "/*/nginx/*.conf"))
                .andExpect(redirectedUrlPattern("/sites/import?scan"));
        mvc.perform(get("/sites/import").param("scan", "").with(user("ed").roles("EDITOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alice/nginx/app.conf")))
                .andExpect(content().string(containsString("bob/nginx/shop.conf")))
                .andExpect(content().string(containsString("carol/nginx/upstreams.conf")))
                .andExpect(content().string(not(containsString("notes.txt"))));

        String home = HOME.toRealPath().toString();
        mvc.perform(post("/sites/import/bulk").with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("paths", home + "/alice/nginx/app.conf", home + "/bob/nginx/shop.conf",
                                home + "/carol/nginx/upstreams.conf")
                        .param("removeSource", "true"))
                .andExpect(redirectedUrlPattern("/sites/import?scan"));

        assertThat(siteRepository.findByPrimaryDomain("app.example.com")).get()
                .extracting(Site::getType).isEqualTo(SiteType.PROXY);
        Site shop = siteRepository.findByPrimaryDomain("shop.example.com").orElseThrow();
        assertThat(shop.getType()).isEqualTo(SiteType.RAW);
        assertThat(shop.getListenHttpPort()).isEqualTo(8081);
        assertThat(shop.getExtraDomains()).isEqualTo("www.shop.example.com");
        assertThat(configFileRepository.findByPath("conf.d/sites/shop.example.com.conf")).get()
                .extracting(ConfigFile::getDraftContent).isEqualTo(RAW);
        // 설정 루트 밖의 원본은 그대로 둔다
        assertThat(Files.readString(HOME.resolve("bob/nginx/shop.conf"))).isEqualTo(RAW);
        assertThat(siteRepository.count()).isEqualTo(2);

        mvc.perform(get("/sites/import").param("scan", "").with(user("ed").roles("EDITOR")))
                .andExpect(content().string(containsString("등록됨")));
    }

    @Test
    void aChangedSourceIsShownAndCanBeImportedAgain() throws Exception {
        writeHome("alice/nginx/app.conf", PROXY);
        String path = HOME.toRealPath().resolve("alice/nginx/app.conf").toString();
        mvc.perform(post("/sites/import").with(csrf()).with(user("ed").roles("EDITOR"))
                .param("path", path).param("contentHash", ConfigFileStore.hash(PROXY)).param("mode", "FORM"));
        assertThat(applyService.applyPending("ed", "import").succeeded()).isTrue();
        Site site = siteRepository.findByPrimaryDomain("app.example.com").orElseThrow();

        writeHome("alice/nginx/app.conf", PROXY.replace("3000", "4000"));
        mvc.perform(get("/sites/{id}", site.getId()).with(user("ed").roles("EDITOR")))
                .andExpect(content().string(containsString("/reimport")));

        mvc.perform(post("/sites/{id}/reimport", site.getId()).with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("version", String.valueOf(site.getVersion())))
                .andExpect(redirectedUrlPattern("/sites/*?saved"));

        Site after = siteRepository.findByPrimaryDomain("app.example.com").orElseThrow();
        assertThat(after.getType()).isEqualTo(SiteType.PROXY);
        assertThat(after.getProxyTarget()).isEqualTo("http://127.0.0.1:4000");
        assertThat(configFileRepository.findByPath("conf.d/sites/app.example.com.conf").orElseThrow().getDraftContent())
                .contains("proxy_pass http://127.0.0.1:4000;");
        mvc.perform(get("/sites/{id}", site.getId()).with(user("ed").roles("EDITOR")))
                .andExpect(content().string(not(containsString("/reimport"))));
    }

    @Test
    void uploadedFilesArePreviewedAndRegistered() throws Exception {
        mvc.perform(multipart("/sites/import/upload").file(new MockMultipartFile("file", "shop.conf",
                        "text/plain", RAW.getBytes())).with(csrf()).with(user("ed").roles("EDITOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("location /static/")));

        mvc.perform(post("/sites/import").with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("upload", RAW).param("uploadName", "shop.conf")
                        .param("contentHash", ConfigFileStore.hash(RAW)).param("mode", "RAW"))
                .andExpect(redirectedUrlPattern("/sites/*?imported"));

        Site shop = siteRepository.findByPrimaryDomain("shop.example.com").orElseThrow();
        assertThat(shop.getType()).isEqualTo(SiteType.RAW);
        assertThat(shop.getImportPath()).isEqualTo("shop.conf");
    }

    @Test
    void refusesConflictsAndFilesOutsideTheAllowedDirectories() throws Exception {
        writeFile("conf.d/app.conf", PROXY);
        writeFile("conf.d/app2.conf", PROXY);
        mvc.perform(post("/sites/import").with(csrf()).with(user("ed").roles("EDITOR"))
                .param("path", "conf.d/app.conf").param("contentHash", ConfigFileStore.hash(PROXY)).param("mode", "FORM"));

        // 같은 도메인은 두 번 등록하지 않는다
        mvc.perform(post("/sites/import").with(csrf()).with(user("ed").roles("EDITOR"))
                        .param("path", "conf.d/app2.conf").param("contentHash", ConfigFileStore.hash(PROXY))
                        .param("mode", "RAW"))
                .andExpect(status().isOk());
        assertThat(siteRepository.count()).isEqualTo(1);

        // 허용한 디렉터리 밖은 찾지도 읽지도 않는다
        mvc.perform(get("/sites/import").param("pattern", "/etc/*").with(user("ed").roles("EDITOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/etc/passwd"))));
        mvc.perform(get("/sites/import/file").param("path", "../../../etc/passwd").with(user("ed").roles("EDITOR")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/sites/import/file").param("path", "nginx.conf").with(user("ed").roles("EDITOR")))
                .andExpect(status().isNotFound());

        // 조회자는 가져오기 화면을 쓸 수 없다(파일 내용을 보여 주므로)
        mvc.perform(get("/sites/import").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
    }

    private static void writeHome(String relative, String content) {
        try {
            Path path = HOME.resolve(relative);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path createHome() {
        try {
            return Files.createTempDirectory("nm-import-home");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
