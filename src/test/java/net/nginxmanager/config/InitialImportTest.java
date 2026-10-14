package net.nginxmanager.config;

import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class InitialImportTest extends ConfigRootTest {

    @Autowired
    InitialImportService importService;
    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;

    @Test
    void importsExistingFilesAsTheyAreAndRegistersRawSites() {
        writeFile("nginx.conf", "events {}\nhttp { include conf.d/sites/*.conf; }\n");
        writeFile("conf.d/sites/app.local.conf", "server { server_name app.local; }\n");
        writeFile("sites-disabled/old.local.conf", "server { server_name old.local; }\n");
        writeFile("conf.d/shared/gzip.conf", "gzip on;\n");
        writeFile("mime.types", "types {}\n");

        assertThat(importService.importIfEmpty()).isTrue();

        assertThat(configFileRepository.findAllByOrderByPathAsc()).extracting(ConfigFile::getPath)
                .containsExactly("conf.d/shared/gzip.conf", "conf.d/sites/app.local.conf", "nginx.conf",
                        "sites-disabled/old.local.conf");
        assertThat(siteRepository.findAll()).extracting(Site::getPrimaryDomain, Site::getType, Site::isEnabled)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("app.local", SiteType.RAW, true),
                        org.assertj.core.groups.Tuple.tuple("old.local", SiteType.RAW, false));
        assertThat(configVersionRepository.findFirstByOrderByIdDesc()).get()
                .extracting(ConfigVersion::getSource).isEqualTo(VersionSource.INITIAL_IMPORT);
        assertThat(readFile("nginx.conf")).isEqualTo("events {}\nhttp { include conf.d/sites/*.conf; }\n");
    }

    @Test
    void doesNothingWhenAlreadyImported() {
        writeFile("nginx.conf", "events {}\n");
        importService.importIfEmpty();

        assertThat(importService.importIfEmpty()).isFalse();
    }

    @Test
    void importedSitesListTheirRealPortAndState() throws Exception {
        writeFile("nginx.conf", "events {}\nhttp { include conf.d/sites/*.conf; }\n");
        writeFile("conf.d/sites/api.local.conf", "server {\n    # listen 80;\n    listen 8443 ssl;\n"
                + "    listen [::]:8088;\n    server_name api.local;\n}\n");
        writeFile("conf.d/sites/web.local.conf", "server { listen 127.0.0.1:8090; server_name web.local; }\n");
        writeFile("sites-disabled/old.local.conf", "server { server_name old.local; }\n");
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();

        importService.importIfEmpty();

        assertThat(siteRepository.findAll()).extracting(Site::getPrimaryDomain, Site::getListenHttpPort)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("api.local", 8088),
                        org.assertj.core.groups.Tuple.tuple("web.local", 8090),
                        // listen이 없으면 nginx 기본값 80
                        org.assertj.core.groups.Tuple.tuple("old.local", 80));
        // 목록에 세 사이트의 도메인·포트·활성 여부가 보인다(US1-1)
        String list = mvc.perform(get("/sites").with(user("admin").roles("ADMIN")))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).contains("api.local", "8088", "web.local", "8090", "old.local");
    }
}
