package net.nginxmanager.history;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.VersionSource;
import net.nginxmanager.config.InitialImportService;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class DriftDetectionTest extends ConfigRootTest {

    @Autowired
    InitialImportService initialImportService;
    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;

    @BeforeEach
    void importExistingConfig() {
        writeFile("nginx.conf", "events {}\n");
        writeFile("conf.d/sites/old.local.conf", "server { server_name old.local; }\n");
        initialImportService.importIfEmpty();
    }

    @Test
    void untouchedConfigHasNoDrift() {
        siteService.create(new SiteForm("app.local", "", SiteType.PROXY, 80, null, "http://a:1", null), "editor");
        applyService.applyPending("editor", null);

        assertThat(driftDetector.refresh()).isEmpty();
    }

    @Test
    void externalEditsAreDetected() throws IOException {
        writeFile("nginx.conf", "events {}\n# edited by hand\n");
        writeFile("conf.d/sites/new.local.conf", "server { server_name new.local; }\n");
        Files.delete(CONFIG_ROOT.resolve("conf.d/sites/old.local.conf"));

        assertThat(driftDetector.refresh()).containsExactlyInAnyOrder(
                new DriftItem("nginx.conf", DriftItem.Kind.MODIFIED),
                new DriftItem("conf.d/sites/new.local.conf", DriftItem.Kind.ADDED),
                new DriftItem("conf.d/sites/old.local.conf", DriftItem.Kind.DELETED));
    }

    @Test
    void importingRecordsNewVersionAndSyncsSites() throws IOException {
        writeFile("nginx.conf", "events {}\n# edited by hand\n");
        writeFile("conf.d/sites/new.local.conf", "server { server_name new.local; }\n");
        Files.delete(CONFIG_ROOT.resolve("conf.d/sites/old.local.conf"));

        Long versionId = driftDetector.importDrift("editor");

        assertThat(driftDetector.refresh()).isEmpty();
        ConfigVersion version = configVersionRepository.findById(versionId).orElseThrow();
        assertThat(version.getSource()).isEqualTo(VersionSource.DRIFT_IMPORT);
        assertThat(siteRepository.findAll()).singleElement()
                .satisfies(site -> {
                    assertThat(site.getPrimaryDomain()).isEqualTo("new.local");
                    assertThat(site.getType()).isEqualTo(SiteType.RAW);
                });
        assertThat(configFileRepository.findByPath("conf.d/sites/old.local.conf")).isEmpty();
    }

    @Test
    void importKeepsDraftsTheUserIsWorkingOn() {
        var file = configFileRepository.findByPath("nginx.conf").orElseThrow();
        siteService.editRaw(file.getId(), "events {}\nhttp {}\n", file.getVersion(), "editor");
        writeFile("nginx.conf", "events {}\n# edited by hand\n");

        driftDetector.importDrift("editor");

        assertThat(configFileRepository.findByPath("nginx.conf").orElseThrow().getDraftContent())
                .isEqualTo("events {}\nhttp {}\n");
        assertThat(driftDetector.refresh()).isEmpty();
    }

    @Test
    void nothingToImportRecordsNothing() {
        long versions = configVersionRepository.count();

        assertThat(driftDetector.importDrift("editor")).isNull();
        assertThat(configVersionRepository.count()).isEqualTo(versions);
    }
}
