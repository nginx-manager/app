package net.nginxmanager.site;

import net.nginxmanager.common.ConcurrentEditException;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.support.ConfigRootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiteServiceTest extends ConfigRootTest {

    @Autowired
    SiteService siteService;

    static SiteForm proxy(String domain, String target, Long version) {
        return new SiteForm(domain, "", SiteType.PROXY, 80, null, target, version);
    }

    @Test
    void createStagesDraftWithoutTouchingDisk() {
        Site site = siteService.create(proxy("App.Local", "http://127.0.0.1:8080", null), "editor");

        assertThat(site.getPrimaryDomain()).isEqualTo("app.local");
        ConfigFile file = configFileRepository.findByPath("conf.d/sites/app.local.conf").orElseThrow();
        assertThat(file.getDraftContent()).contains("proxy_pass http://127.0.0.1:8080;");
        assertThat(readFile("conf.d/sites/app.local.conf")).isNull();
    }

    @Test
    void duplicateDomainIsRejected() {
        siteService.create(proxy("app.local", "http://a:1", null), "editor");

        assertThatThrownBy(() -> siteService.create(proxy("app.local", "http://b:2", null), "editor"))
                .isInstanceOf(DuplicateSiteException.class);
    }

    @Test
    void staleVersionIsRejectedWithoutOverwriting() {
        Site site = siteService.create(proxy("app.local", "http://a:1", null), "editor");
        long readVersion = siteRepository.findById(site.getId()).orElseThrow().getVersion();
        siteService.update(site.getId(), proxy("app.local", "http://first:1", readVersion), "alice");

        assertThatThrownBy(() -> siteService.update(site.getId(), proxy("app.local", "http://second:2", readVersion), "bob"))
                .isInstanceOf(ConcurrentEditException.class);
        assertThat(configFileRepository.findByPath("conf.d/sites/app.local.conf").orElseThrow().getDraftContent())
                .contains("http://first:1");
    }

    @Test
    void rawEditSwitchesFormSiteToRaw() {
        Site site = siteService.create(proxy("app.local", "http://a:1", null), "editor");
        ConfigFile file = configFileRepository.findByPath("conf.d/sites/app.local.conf").orElseThrow();

        siteService.editRaw(file.getId(), "server { listen 81; }\r\n", file.getVersion(), "editor");

        assertThat(siteRepository.findById(site.getId()).orElseThrow().getType()).isEqualTo(SiteType.RAW);
        assertThat(configFileRepository.findById(file.getId()).orElseThrow().getDraftContent())
                .isEqualTo("server { listen 81; }\n");
    }

    @Test
    void disablingMovesFileToDisabledDirectory() {
        writeFile("conf.d/sites/app.local.conf", "server { listen 80; }\n");
        ConfigFile live = configFileRepository.save(new ConfigFile("conf.d/sites/app.local.conf", false));
        Site site = siteRepository.save(new Site("app.local", SiteType.RAW, live));

        siteService.setEnabled(site.getId(), false, site.getVersion(), "editor");

        assertThat(configFileRepository.findByPath("conf.d/sites/app.local.conf").orElseThrow().isDraftDelete()).isTrue();
        assertThat(configFileRepository.findByPath("sites-disabled/app.local.conf").orElseThrow().getDraftContent())
                .isEqualTo("server { listen 80; }\n");
        assertThat(siteRepository.findById(site.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void discardingNeverAppliedSiteRemovesIt() {
        Site site = siteService.create(proxy("app.local", "http://a:1", null), "editor");
        ConfigFile file = configFileRepository.findByPath("conf.d/sites/app.local.conf").orElseThrow();

        siteService.discard(file.getId(), "editor");

        assertThat(siteRepository.findById(site.getId())).isEmpty();
        assertThat(configFileRepository.findById(file.getId())).isEmpty();
    }
}
