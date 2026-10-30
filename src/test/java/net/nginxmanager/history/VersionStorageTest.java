package net.nginxmanager.history;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.ConfigVersionFile;
import net.nginxmanager.config.VersionContents;
import net.nginxmanager.config.VersionResult;
import net.nginxmanager.config.VersionSource;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.QueryCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 같은 내용은 한 번만 저장하고, 버전은 여전히 그 시점의 전체 파일을 담는다(012). */
class VersionStorageTest extends ConfigRootTest {

    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;
    @Autowired
    VersionQueryRepository versionQueryRepository;
    @Autowired
    VersionContents versionContents;
    @Autowired
    QueryCounter queryCounter;
    @Autowired
    TransactionTemplate tx;

    static SiteForm proxy(String domain, String target, Long version) {
        return new SiteForm(domain, "", SiteType.PROXY, 80, null, target, version);
    }

    private Site site(String domain) {
        return siteRepository.findByPrimaryDomain(domain).orElseThrow();
    }

    @Test
    void unchangedFilesAreNotStoredAgain() {
        for (int i = 0; i < 5; i++) {
            siteService.create(proxy("s" + i + ".local", "http://127.0.0.1:800" + i, null), "editor");
        }
        assertThat(applyService.applyPending("editor", "sites").succeeded()).isTrue();
        long stored = configContentRepository.count();
        long files = configFileRepository.count();

        long last = 0;
        for (int i = 1; i <= 3; i++) {
            siteService.update(site("s0.local").getId(), proxy("s0.local", "http://127.0.0.1:900" + i,
                    site("s0.local").getVersion()), "editor");
            last = applyService.applyPending("editor", "change " + i).versionId();
        }

        // 바뀐 파일 내용만 새로 저장한다
        assertThat(configContentRepository.count()).isEqualTo(stored + 3);
        // 버전은 여전히 관리 파일 전체의 스냅샷이다
        ConfigVersion version = versionQueryRepository.findWithFiles(last).orElseThrow();
        assertThat(version.getFiles()).hasSize((int) files);
        assertThat(version.getFiles()).filteredOn(f -> f.getPath().contains("s0.local"))
                .extracting(ConfigVersionFile::getContent).singleElement().asString().contains("9003");
    }

    @Test
    void sameContentInOneVersionIsStoredOnce() {
        tx.executeWithoutResult(status -> {
            ConfigVersion version = new ConfigVersion(null, "same", VersionSource.APPLY, VersionResult.SUCCESS, null);
            versionContents.attach(version, Map.of("a.conf", "same\n", "b.conf", "same\n"));
            configVersionRepository.save(version);
        });
        assertThat(configContentRepository.count()).isEqualTo(1);
    }

    @Test
    void readingAVersionWithContentsDoesNotGrowWithFiles() {
        long small = saveVersion(2);
        long large = saveVersion(30);
        long smallQueries = queryCounter.count(() -> readAll(small));
        long largeQueries = queryCounter.count(() -> readAll(large));
        assertThat(largeQueries).isEqualTo(smallQueries);
    }

    private long saveVersion(int files) {
        return tx.execute(status -> {
            ConfigVersion version = new ConfigVersion(null, "v", VersionSource.APPLY, VersionResult.SUCCESS, null);
            Map<String, String> contents = new LinkedHashMap<>();
            for (int i = 0; i < files; i++) {
                contents.put("conf.d/f" + i + ".conf", "# " + files + "-" + i + " " + System.nanoTime() + "\n");
            }
            versionContents.attach(version, contents);
            return configVersionRepository.save(version).getId();
        });
    }

    private void readAll(long id) {
        tx.executeWithoutResult(status -> versionQueryRepository.findWithFiles(id).orElseThrow()
                .getFiles().forEach(ConfigVersionFile::getContent));
    }
}
