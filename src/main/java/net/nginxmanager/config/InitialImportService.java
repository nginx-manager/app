package net.nginxmanager.config;

import net.nginxmanager.site.Site;
import net.nginxmanager.site.SitePaths;
import net.nginxmanager.site.SiteRepository;
import net.nginxmanager.site.SiteType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 처음 실행할 때 설정 루트의 *.conf 파일을 그대로 가져온다. 파일 구조는 건드리지 않고,
 * 사이트 디렉터리의 파일은 원문 편집 전용(RAW) 사이트로 등록한다.
 */
@Component
public class InitialImportService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialImportService.class);

    private final ConfigFileRepository configFileRepository;
    private final ConfigVersionRepository versionRepository;
    private final SiteRepository siteRepository;
    private final ConfigFileStore store;

    public InitialImportService(ConfigFileRepository configFileRepository, ConfigVersionRepository versionRepository,
                                SiteRepository siteRepository, ConfigFileStore store) {
        this.configFileRepository = configFileRepository;
        this.versionRepository = versionRepository;
        this.siteRepository = siteRepository;
        this.store = store;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        importIfEmpty();
    }

    @Transactional
    public boolean importIfEmpty() {
        if (configFileRepository.count() > 0) {
            return false;
        }
        List<String> paths = store.listConfFiles();
        if (paths.isEmpty()) {
            return false;
        }
        ConfigVersion version = new ConfigVersion(null, "기존 설정 가져오기", VersionSource.INITIAL_IMPORT,
                VersionResult.SUCCESS, null);
        for (String path : paths) {
            String content = store.read(path);
            ConfigFile file = new ConfigFile(path, false);
            file.markImported(ConfigFileStore.hash(content));
            configFileRepository.save(file);
            version.addFile(path, content);
            String domain = SitePaths.domainOf(path);
            if (domain != null && !siteRepository.existsByPrimaryDomain(domain)) {
                Site site = new Site(domain, SiteType.RAW, file);
                if (path.startsWith(SitePaths.DISABLED_DIR)) {
                    site.markImportedDisabled();
                }
                siteRepository.save(site);
            }
        }
        versionRepository.save(version);
        log.info("Imported {} nginx config files from {}", paths.size(), store.root());
        return true;
    }
}
