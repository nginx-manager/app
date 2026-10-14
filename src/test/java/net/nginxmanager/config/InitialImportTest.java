package net.nginxmanager.config;

import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class InitialImportTest extends ConfigRootTest {

    @Autowired
    InitialImportService importService;

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
}
